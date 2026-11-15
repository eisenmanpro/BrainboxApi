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
 * Runs a report job end to end: assemble the data, render one PDF per artifact, store the
 * result and move the job to READY. Per-class tables with "All" (D19) render one PDF per
 * class and are stored as a single deterministic zip, so polling, history and the signed
 * download route keep working unchanged. Cancellation is honoured before the file is
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
        val pdfName = jobId.toString() + ".pdf"
        val zipName = jobId.toString() + ".zip"
        try {
            val request = mapper.readValue(job.payloadJson ?: "{}", ReportGenerationRequestPayload::class.java)
            val owner = userRepository.findById(job.ownerId).orElse(null)
                ?: return state.markFailed(jobId, "Report owner no longer exists")
            val actor = CurrentUser(owner.id, owner.role, owner.subRole)
            val branding = ReportBranding.from(schoolConfig.branding(job.schoolId))
            val artifacts = data.buildArtifacts(request, actor, branding)
            val rendered = artifacts.map { artifact -> artifact to renderer.renderArtifact(artifact) }
            if (state.isCancelRequested(jobId)) {
                storage.delete(pdfName)
                storage.delete(zipName)
                return
            }
            val single = rendered.singleOrNull()
            if (single != null) {
                val (artifact, bytes) = single
                val size = storage.save(pdfName, bytes)
                state.markReady(jobId, fileNameFor(artifact, request), size, pdfName)
                return
            }
            val entries = rendered.map { (artifact, bytes) -> fileNameFor(artifact, request) to bytes }
            val bundle = ReportBundles.zip(entries)
            val bundleName = bundleFileName(rendered.first().first, request)
            val size = storage.save(zipName, bundle, "application/zip")
            state.markReady(jobId, bundleName, size, zipName)
        } catch (e: Exception) {
            storage.delete(pdfName)
            storage.delete(zipName)
            state.markFailed(jobId, e.message ?: "Report generation failed")
        }
    }

    /** Class files are self-describing (`5_Lion_<exam>_<term>.pdf`), other reports keep their name. */
    private fun fileNameFor(artifact: ReportArtifact, request: ReportGenerationRequestPayload): String {
        val classTag = artifact.classTag ?: return ReportFileNames.forRequest(request)
        val exam = (artifact.spec as? GradeTableSpec)?.exam
        return ReportFileNames.forClass(classTag, exam?.title ?: artifact.spec.title, examTerm(exam, request))
    }

    private fun bundleFileName(first: ReportArtifact, request: ReportGenerationRequestPayload): String {
        val exam = (first.spec as? GradeTableSpec)?.exam
        return ReportFileNames.forBundle(exam?.title ?: first.spec.title, examTerm(exam, request))
    }

    private fun examTerm(exam: com.afrithecus.brainbox.api.traditional.web.TraditionalExamDto?, request: ReportGenerationRequestPayload): String =
        exam?.let { it.term.name + " " + it.year } ?: request.term
}
