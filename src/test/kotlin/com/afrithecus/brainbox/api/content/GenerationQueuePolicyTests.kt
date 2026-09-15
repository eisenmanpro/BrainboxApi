package com.afrithecus.brainbox.api.content

import com.afrithecus.brainbox.api.content.ai.ContentGenerationProvider
import com.afrithecus.brainbox.api.content.ai.GenerationRequest
import com.afrithecus.brainbox.api.content.entity.ConceptEntity
import com.afrithecus.brainbox.api.content.entity.CurriculumMapEntity
import com.afrithecus.brainbox.api.content.repository.ConceptRepository
import com.afrithecus.brainbox.api.content.repository.ContentUnitRepository
import com.afrithecus.brainbox.api.content.repository.CurriculumMapRepository
import com.afrithecus.brainbox.api.content.repository.GenerationJobRepository
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.annotation.Transactional

/**
 * H2 runtime policy for the generation queue: pause/resume, the per-source claim
 * filter and USER-first priority. Reuses the router tests' fake provider so no
 * network call is made.
 */
@SpringBootTest(properties = ["app.content.run-mode=both", "app.content.worker.retry-backoff-seconds=0"])
@ActiveProfiles("test")
@Transactional
@Import(ContentRouterTests.FakeProviderConfig::class)
class GenerationQueuePolicyTests(
    @Autowired private val worker: GenerationJobWorker,
    @Autowired private val jobService: GenerationJobService,
    @Autowired private val provider: ContentGenerationProvider,
    @Autowired private val generationJobs: GenerationJobRepository,
    @Autowired private val contentUnits: ContentUnitRepository,
    @Autowired private val concepts: ConceptRepository,
    @Autowired private val curriculumMaps: CurriculumMapRepository,
    @Autowired private val moderationPolicy: ModerationPolicyService,
    @Autowired private val entityManager: EntityManager,
) {

    private val fake: ContentRouterTests.FakeContentGenerationProvider
        get() = provider as ContentRouterTests.FakeContentGenerationProvider

    @BeforeEach
    fun setUp() {
        fake.requests.clear()
        fake.failNext = null
        fake.clean = false
        fake.verificationRequests.clear()
        fake.verificationCalls = 0
        fake.verifyFailNext = null
        fake.verificationAnswers = null

        val concept = concepts.save(
            ConceptEntity().apply {
                code = "MAT-FRAC-01"
                name = "Fractions"
                description = "Compare, order and compute with fractions."
                subject = "Mathematics"
                sortOrder = 1
            }
        )
        curriculumMaps.save(
            CurriculumMapEntity().apply {
                conceptId = concept.id
                countryCode = "KE"
                curriculum = "CBC"
                gradeLevel = "Grade 4"
                strandCode = "MAT-NUM-FRAC"
                strandName = "Fractions"
                learningOutcome = "Compare fractions with unlike denominators."
                sortOrder = 1
            }
        )
    }

    private fun request(key: String) = GenerationRequest(
        generationKey = key,
        taskType = "LESSON",
        taskTypeLabel = "Lesson",
        conceptCode = "MAT-FRAC-01",
        conceptName = "Fractions",
        subject = "Mathematics",
        gradeLevel = "Grade 4",
        language = "en",
        standardVersion = "v1",
        difficulty = 3,
        notes = "Keep it short.",
    )

    /** Flushes and reloads so assertions read the committed row, not the context copy. */
    private fun reload(id: java.util.UUID) = generationJobs.findById(id).orElseThrow().also {
        entityManager.flush()
        entityManager.clear()
    }

    @Test
    fun `pausing stops the worker claiming and resuming drains the job`() {
        val key = "ke:cbc:grade4:mat-num-frac:lesson:h2-pause"
        val job = jobService.enqueue(request(key))

        moderationPolicy.setContentWorkerPaused(true)
        worker.poll()

        val paused = reload(job.id)
        check(paused.status == "QUEUED")
        check(paused.attempts == 0)
        check(fake.requests.isEmpty()) { "a paused worker must not call the provider" }

        moderationPolicy.setContentWorkerPaused(false)
        worker.poll()

        val resumed = reload(job.id)
        check(resumed.status == "SUCCEEDED")
        check(resumed.attempts == 1)
        check(fake.requests.size == 1)
        check(contentUnits.findByGenerationKey(key) != null)
    }

    @Test
    fun `disabling a source stops that source being claimed while an enabled source drains`() {
        val userKey = "ke:cbc:grade4:mat-num-frac:lesson:h2-source-user"
        val batchKey = "ke:cbc:grade4:mat-num-frac:lesson:h2-source-batch"
        val user = jobService.enqueue(request(userKey), GenerationJobSource.USER)
        val batch = jobService.enqueue(request(batchKey), GenerationJobSource.BATCH)

        // Only USER enabled: the user job drains, the batch job is untouched.
        moderationPolicy.setContentWorkerSources(listOf(GenerationJobSource.USER))
        worker.poll()

        check(reload(user.id).status == "SUCCEEDED")
        val stillQueued = reload(batch.id)
        check(stillQueued.status == "QUEUED")
        check(stillQueued.attempts == 0)
        check(fake.requests.size == 1)

        // Switch the enabled source: now the batch job drains.
        moderationPolicy.setContentWorkerSources(listOf(GenerationJobSource.BATCH))
        worker.poll()

        check(reload(batch.id).status == "SUCCEEDED")
        check(fake.requests.size == 2)
    }

    @Test
    fun `a user job is claimed ahead of an earlier batch job`() {
        val batch = jobService.enqueue(
            request("ke:cbc:grade4:mat-num-frac:lesson:h2-priority-batch"),
            GenerationJobSource.BATCH,
        )
        val user = jobService.enqueue(
            request("ke:cbc:grade4:mat-num-frac:lesson:h2-priority-user"),
            GenerationJobSource.USER,
        )
        check(batch.createdAt <= user.createdAt)

        val claimed = jobService.claim(1, GenerationJobSource.ALL)

        check(claimed.size == 1)
        check(claimed.single().id == user.id) { "the first claim must be the USER job" }
        check(claimed.single().source == GenerationJobSource.USER)
    }
}
