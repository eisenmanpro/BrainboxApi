package com.afrithecus.brainbox.api.content

import com.afrithecus.brainbox.api.common.error.invalidArgument
import com.afrithecus.brainbox.api.content.repository.GenerationJobRepository
import com.afrithecus.brainbox.api.content.web.SubjectAgentSummary
import com.afrithecus.brainbox.api.content.web.ContentQueueBudget
import com.afrithecus.brainbox.api.content.web.ContentQueueBudgetRequest
import com.afrithecus.brainbox.api.content.web.ContentQueueSummary
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * H2 admin runtime surface for the generation queue. It reads and writes the
 * operational policy keys through [ModerationPolicyService] (same
 * `moderation_policies` table as the moderation keys) and reports the queue
 * depth overall and per source from aggregate queries on `generation_jobs`.
 *
 * No client change is required: this is an operator control-plane surface that
 * decides whether the worker drains autonomous work, not a change to any
 * teacher/student contract.
 */
@Service
class ContentQueueAdminService(
    private val generationJobs: GenerationJobRepository,
    private val moderationPolicy: ModerationPolicyService,
    private val properties: AppContentProperties,
    private val budgets: GenerationBudgetService,
    private val agentRuns: com.afrithecus.brainbox.api.content.repository.AgentRunRepository,
    private val subjectAgents: com.afrithecus.brainbox.api.content.subject.SubjectAgentRegistry,
) {

    /**
     * The domain subject agents and how much work each has actually produced. This is the
     * console's view over the capture tables: the roster comes from code (a deployable
     * artifact), the counts from `agent_runs.agent_code`.
     *
     * Runs captured before attribution existed have no agent code; they are folded into
     * the GENERAL row rather than dropped, so the totals still add up.
     */
    @Transactional(readOnly = true)
    fun agents(): List<SubjectAgentSummary> {
        val counts = agentRuns.countsByAgent()
        val byCode = counts.mapNotNull { row -> row.getAgentCode()?.let { code -> code to row.getTotal() } }.toMap()
        val unattributed = counts.filter { it.getAgentCode() == null }.sumOf { it.getTotal() }
        return subjectAgents.roster().map { agent ->
            val runs = byCode[agent.code] ?: 0L
            SubjectAgentSummary(
                code = agent.code,
                displayName = agent.displayName,
                subjects = agent.subjects.sorted(),
                runs = if (agent.code == subjectAgents.generalCode) runs + unattributed else runs,
            )
        }
    }

    /** Live policy plus queue depth; a pure read, no writes. */
    @Transactional(readOnly = true)
    fun summary(): ContentQueueSummary =
        ContentQueueSummary(
            paused = moderationPolicy.contentWorkerPaused(properties.worker.paused),
            sources = moderationPolicy.contentWorkerSources(properties.worker.sources),
            depth = depthByStatus(),
            bySource = depthBySource(),
        )

    /** Sets `content_worker_paused=true` and returns the new summary. */
    @Transactional
    fun pause(): ContentQueueSummary {
        moderationPolicy.setContentWorkerPaused(true)
        return summary()
    }

    /** Sets `content_worker_paused=false` and returns the new summary. */
    @Transactional
    fun resume(): ContentQueueSummary {
        moderationPolicy.setContentWorkerPaused(false)
        return summary()
    }

    /**
     * Replaces `content_worker_sources` with [sources] (trimmed, upper-cased,
     * de-duplicated) and returns the new summary. An unknown source name is an
     * invalid argument; a null body value is required; an empty list is allowed
     * and claims nothing.
     */
    @Transactional
    fun setSources(sources: List<String>?): ContentQueueSummary {
        if (sources == null) throw invalidArgument("sources is required")
        val normalized = sources
            .map { it.trim().uppercase() }
            .filter { it.isNotEmpty() }
            .distinct()
        val unknown = normalized.filterNot { GenerationJobSource.isKnown(it) }
        if (unknown.isNotEmpty()) {
            throw invalidArgument(
                "unknown source(s): " + unknown.joinToString(", ") +
                    "; supported: " + GenerationJobSource.ALL.joinToString(", ")
            )
        }
        moderationPolicy.setContentWorkerSources(normalized)
        return summary()
    }

    /** H3: effective daily job and spend budgets plus today's usage and schools over budget. */
    @Transactional(readOnly = true)
    fun budget(): ContentQueueBudget = ContentQueueBudget(
        platformBudget = budgets.platformBudget(),
        platformUsed = budgets.platformUsedToday(),
        schoolBudget = budgets.schoolBudget(),
        schoolsOverBudget = budgets.schoolsOverBudget(),
        platformCostBudgetMicros = budgets.platformCostBudget(),
        platformCostUsedMicros = budgets.platformCostUsedToday(),
        schoolCostBudgetMicros = budgets.schoolCostBudget(),
    )

    /** H3: sets one or more daily budgets and returns the new snapshot. */
    @Transactional
    fun setBudget(request: ContentQueueBudgetRequest): ContentQueueBudget {
        budgets.setBudgets(
            request.platformDailyJobs,
            request.schoolDailyJobs,
            request.platformDailyCostMicros,
            request.schoolDailyCostMicros,
        )
        return budget()
    }

    /** Every canonical status is present so the shape is stable for an operator UI. */
    private fun depthByStatus(): Map<String, Long> {
        val depth = linkedMapOf<String, Long>()
        ContentQueueMetrics.STATUSES.forEach { status -> depth[status] = 0L }
        generationJobs.countGroupedByStatus().forEach { row -> depth[row.status] = row.total }
        return depth
    }

    /** Every canonical source and status is present; unknown DB rows are still reported. */
    private fun depthBySource(): Map<String, Map<String, Long>> {
        val bySource = linkedMapOf<String, MutableMap<String, Long>>()
        GenerationJobSource.ALL.forEach { source ->
            val statuses = linkedMapOf<String, Long>()
            ContentQueueMetrics.STATUSES.forEach { status -> statuses[status] = 0L }
            bySource[source] = statuses
        }
        generationJobs.countGroupedBySourceAndStatus().forEach { row ->
            val statuses = bySource.getOrPut(row.source) {
                linkedMapOf<String, Long>().apply {
                    ContentQueueMetrics.STATUSES.forEach { status -> put(status, 0L) }
                }
            }
            statuses[row.status] = row.total
        }
        return bySource
    }
}
