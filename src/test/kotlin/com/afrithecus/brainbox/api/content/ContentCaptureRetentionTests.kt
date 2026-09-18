package com.afrithecus.brainbox.api.content

import com.afrithecus.brainbox.api.content.entity.AgentRunEntity
import com.afrithecus.brainbox.api.content.entity.ModelCallEntity
import com.afrithecus.brainbox.api.content.repository.AgentRunRepository
import com.afrithecus.brainbox.api.content.repository.ModelCallRepository
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

/**
 * Capture retention: content is served from the cache, so the storage story is
 * bounded by pruning the observability capture. Deleting a run must also remove
 * its model/tool calls through the foreign-key cascade, in one sweep.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ContentCaptureRetentionTests(
    @Autowired private val retention: ContentCaptureScheduler,
    @Autowired private val agentRuns: AgentRunRepository,
    @Autowired private val modelCalls: ModelCallRepository,
    @Autowired private val clock: Clock,
) {

    @Test
    fun pruningRemovesCaptureRunsAndCascadesTheirModelCalls() {
        val run = agentRuns.save(
            AgentRunEntity().apply {
                generationKey = "ke:cbc:grade4:mat-num-frac:retention"
                status = "SUCCEEDED"
            }
        )
        modelCalls.save(
            ModelCallEntity().apply {
                agentRunId = run.id
                provider = "fake"
                costMicros = 1
            }
        )
        check(modelCalls.findAllByAgentRunId(run.id).isNotEmpty())

        // A future cutoff matches the just-created run, exercising the exact delete
        // the scheduled sweep runs without waiting out the retention window.
        retention.pruneBefore(clock.instant().plusSeconds(60))

        check(agentRuns.findById(run.id).isEmpty) { "the old run must be pruned" }
        check(modelCalls.findAllByAgentRunId(run.id).isEmpty()) { "model calls must cascade with the run" }
    }
}
