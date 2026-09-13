package com.afrithecus.brainbox.api.report

import com.afrithecus.brainbox.api.identity.SchoolConfigService
import com.afrithecus.brainbox.api.identity.model.CurrentUser
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import com.afrithecus.brainbox.api.report.web.ReportGenerationRequestPayload
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper
import java.util.UUID

/**
 * Runs a report job end to end: assemble data, render the branded PDF, store it
 * and move the job to READY. Cancellation is honoured before the file is
 * committed. Runs on the bounded report executor for server-side jobs.
 */
@Service
class ReportJobRunner(
    private val state: ReportJobStateService,
    private val data: ReportDataService,
    private val renderer: ReportRenderer,
    private val storage: ReportStorage,
    private val schoolConfig: SchoolConfigService,
    private val userRepository: UserRepository,
    private val mapper: ObjectMapper,
    @Qualifier("reportExecutor") private val executor: ThreadPoolTaskExecutor,
) {

    fun submit(jobId: UUID) {
        executor.execute { run(jobId) }
    }

    fun run(jobId: UUID) {
        val job = state.markRunning(jobId) ?: return
        val storageName = jobId.toString() + ".pdf"
        try {
            val request = mapper.readValue(job.payloadJson ?: "{}", ReportGenerationRequestPayload::class.java)
            val owner = userRepository.findById(job.ownerId).orElse(null)
                ?: return state.markFailed(jobId, "Report owner no longer exists")
            val actor = CurrentUser(owner.id, owner.role, owner.subRole)
            val branding = ReportBranding.from(schoolConfig.branding(job.schoolId))
            val spec = data.build(request, actor, branding)
            val bytes = renderer.render(spec)
            if (state.isCancelRequested(jobId)) {
                storage.delete(storageName)
                return
            }
            val size = storage.save(storageName, bytes)
            state.markReady(jobId, ReportFileNames.forRequest(request), size, storageName)
        } catch (e: Exception) {
            storage.delete(storageName)
            state.markFailed(jobId, e.message ?: "Report generation failed")
        }
    }
}
