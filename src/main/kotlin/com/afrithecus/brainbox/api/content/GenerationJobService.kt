package com.afrithecus.brainbox.api.content

import com.afrithecus.brainbox.api.content.ai.GenerationRequest
import com.afrithecus.brainbox.api.content.entity.GenerationJobEntity
import com.afrithecus.brainbox.api.content.repository.ConceptRepository
import com.afrithecus.brainbox.api.content.repository.GenerationJobRepository
import org.slf4j.LoggerFactory
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.util.UUID

/**
 * Phase 7.5a durable generation queue. One row per generation key is reused
 * (idempotent enqueue); the worker claims the oldest eligible QUEUED row and
 * either completes it or schedules a bounded retry. All state lives in
 * `generation_jobs`, so any JVM can drain the queue and a crashed RUNNING job is
 * reclaimed once it goes stale.
 */
@Service
class GenerationJobService(
    private val generationJobs: GenerationJobRepository,
    private val concepts: ConceptRepository,
    private val properties: AppContentProperties,
    private val mapper: ObjectMapper,
    private val clock: Clock,
    /** H3 daily budget/backpressure gate; enforced before a new row is written. */
    private val budget: GenerationBudgetService,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Idempotent enqueue for a generation key: the newest existing row is reused,
     * a SUCCEEDED row is left untouched, and every other row is reset to QUEUED
     * with a fresh attempt budget and the full request payload.
     *
     * H2: the job records [source] (default [GenerationJobSource.USER]). An
     * existing row is never downgraded to a lower-priority source, so a batch
     * re-enqueue cannot turn a queued interactive request into BATCH work.
     *
     * H3: [schoolId] is the requesting user's school when known and is the
     * attribution for the per-school daily budget. A new row is charged against the
     * daily budgets and an exhausted budget throws a 429 before anything is written;
     * re-enqueuing an existing generation key creates no new work and is therefore
     * never blocked by the budget.
     */
    fun enqueue(
        request: GenerationRequest,
        source: String = GenerationJobSource.USER,
        schoolId: UUID? = null,
    ): GenerationJobEntity {
        val requestedSource = GenerationJobSource.normalize(source)
        val existing = generationJobs
            .findAllByGenerationKeyOrderByCreatedAtAsc(request.generationKey)
            .lastOrNull()
        if (existing != null && existing.status == STATUS_SUCCEEDED) return existing

        // H3: idempotency before budget. Only a brand-new key spends budget.
        if (existing == null) budget.enforce(schoolId)

        val job = existing ?: GenerationJobEntity()
        if (schoolId != null) job.schoolId = schoolId
        job.generationKey = request.generationKey
        job.taskType = request.taskType
        job.conceptId = conceptId(request.conceptCode)
        job.gradeLevel = request.gradeLevel
        job.status = STATUS_QUEUED
        job.source = effectiveSource(existing?.source, requestedSource)
        job.attempts = 0
        job.nextAttemptAt = null
        job.lastError = null
        job.maxAttempts = properties.worker.maxAttempts
        job.requestPayload = mapper.writeValueAsString(request)
        return generationJobs.save(job)
    }

    /** Keeps the higher-priority source when a key already exists. */
    private fun effectiveSource(existing: String?, requested: String): String =
        if (existing == null) requested
        else if (GenerationJobSource.priority(requested) < GenerationJobSource.priority(existing)) requested
        else existing

    /** Looks up one job for the poll surface; null when it does not exist. */
    fun find(jobId: UUID): GenerationJobEntity? = generationJobs.findById(jobId).orElse(null)

    /** Decodes the stored request; null on a blank or malformed payload. */
    fun decode(job: GenerationJobEntity): GenerationRequest? {
        val payload = job.requestPayload?.takeIf { it.isNotBlank() } ?: return null
        return runCatching { mapper.readValue(payload, GenerationRequest::class.java) }.getOrNull()
    }

    /**
     * Claims up to [batchSize] eligible jobs in one transaction. Only jobs whose
     * [sources] are currently enabled are eligible; within them USER is claimed
     * ahead of BATCH/PROACTIVE. Each row flushes on its own so an optimistic-lock
     * conflict (another worker took it) is caught per row and does not poison the
     * rest of the batch.
     */
    @Transactional
    fun claim(batchSize: Int, sources: Collection<String> = GenerationJobSource.ALL): List<GenerationJobEntity> {
        if (batchSize <= 0) return emptyList()
        val enabled = sources.map { GenerationJobSource.normalize(it) }.distinct()
        if (enabled.isEmpty()) return emptyList()
        val claimable = generationJobs.findClaimable(clock.instant(), enabled, PageRequest.of(0, batchSize))
        val claimed = mutableListOf<GenerationJobEntity>()
        for (job in claimable) {
            try {
                job.status = STATUS_RUNNING
                job.attempts = job.attempts + 1
                generationJobs.saveAndFlush(job)
                claimed += job
            } catch (conflict: OptimisticLockingFailureException) {
                log.debug("generation job {} was claimed by another worker", job.id)
            }
        }
        return claimed
    }

    /** Marks a claimed job SUCCEEDED; never leaves a stale retry schedule behind. */
    @Transactional
    fun complete(jobId: UUID, runId: UUID? = null) {
        val job = generationJobs.findById(jobId).orElse(null) ?: return
        job.status = STATUS_SUCCEEDED
        job.nextAttemptAt = null
        job.lastError = null
        if (runId != null) job.runId = runId
        generationJobs.save(job)
    }

    /**
     * Records a failure without throwing. Below the attempt budget the job goes
     * back to QUEUED behind an increasing backoff; at the budget it is FAILED.
     */
    @Transactional
    fun fail(jobId: UUID, error: String) {
        try {
            val job = generationJobs.findById(jobId).orElse(null) ?: return
            job.lastError = error.take(MAX_ERROR_CHARS)
            if (job.attempts < job.maxAttempts) {
                job.status = STATUS_QUEUED
                job.nextAttemptAt = clock.instant()
                    .plusSeconds(properties.worker.retryBackoffSeconds * job.attempts)
            } else {
                job.status = STATUS_FAILED
            }
            generationJobs.save(job)
        } catch (failure: Exception) {
            log.warn("could not record generation job {} failure: {}", jobId, failure.message)
        }
    }

    /** Returns RUNNING jobs idle for [staleSeconds] to QUEUED (attempts unchanged). */
    @Transactional
    fun reclaimStale(staleSeconds: Long) {
        val cutoff = clock.instant().minusSeconds(staleSeconds)
        for (job in generationJobs.findStaleRunning(cutoff)) {
            job.status = STATUS_QUEUED
            generationJobs.save(job)
        }
    }

    private fun conceptId(code: String?): UUID? =
        code?.takeIf { it.isNotBlank() }?.let { concepts.findByCode(it)?.id }

    private companion object {
        const val STATUS_QUEUED = "QUEUED"
        const val STATUS_RUNNING = "RUNNING"
        const val STATUS_SUCCEEDED = "SUCCEEDED"
        const val STATUS_FAILED = "FAILED"
        const val MAX_ERROR_CHARS = 2000
    }
}
