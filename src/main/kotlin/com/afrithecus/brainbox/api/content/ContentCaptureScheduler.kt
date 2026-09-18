package com.afrithecus.brainbox.api.content

import com.afrithecus.brainbox.api.content.repository.AgentRunRepository
import com.afrithecus.brainbox.api.content.repository.ModelCallRepository
import com.afrithecus.brainbox.api.content.repository.ToolCallRepository
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * Bounds the generation capture footprint. Generated content itself is the cache
 * (content_units), served on demand and never stored as a rendered document, so
 * the only unbounded storage is the observability capture: agent_runs and their
 * child model_calls/tool_calls. This daily sweep removes runs older than
 * app.content.retention.capture-days; the child rows are removed first, in the
 * same transaction, so the sweep does not depend on cascade behaviour.
 */
@Component
class ContentCaptureScheduler(
    private val agentRuns: AgentRunRepository,
    private val modelCalls: ModelCallRepository,
    private val toolCalls: ToolCallRepository,
    private val properties: AppContentProperties,
    private val clock: Clock,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(initialDelay = 600_000, fixedDelay = 86_400_000)
    fun prune() {
        val days = properties.retention.captureDays
        if (days <= 0) return
        pruneBefore(clock.instant().minus(Duration.ofDays(days)))
    }

    /**
     * Retention with an explicit cutoff, for tests and manual maintenance. The
     * children are removed first so the sweep does not depend on the database's
     * cascade behaviour, then the runs themselves.
     */
    @Transactional
    fun pruneBefore(cutoff: Instant) {
        val calls = modelCalls.deleteByRunCreatedAtBefore(cutoff)
        val tools = toolCalls.deleteByRunCreatedAtBefore(cutoff)
        val removed = agentRuns.deleteByCreatedAtBefore(cutoff)
        if (removed > 0 || calls > 0 || tools > 0) {
            log.info(
                "pruned {} capture runs ({} model calls, {} tool calls) created before {}",
                removed,
                calls,
                tools,
                cutoff,
            )
        }
    }
}
