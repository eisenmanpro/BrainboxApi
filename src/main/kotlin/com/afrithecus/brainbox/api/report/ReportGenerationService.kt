package com.afrithecus.brainbox.api.report

import com.afrithecus.brainbox.api.common.error.ApiErrorCode
import com.afrithecus.brainbox.api.common.error.ApiException
import com.afrithecus.brainbox.api.common.error.invalidArgument
import com.afrithecus.brainbox.api.common.error.notFound
import com.afrithecus.brainbox.api.identity.SchoolConfigService
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.model.CurrentUser
import com.afrithecus.brainbox.api.identity.model.Role
import com.afrithecus.brainbox.api.identity.model.SubRole
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import com.afrithecus.brainbox.api.report.entity.ReportJobEntity
import com.afrithecus.brainbox.api.report.entity.ReportScheduleEntity
import com.afrithecus.brainbox.api.report.repository.ReportJobRepository
import com.afrithecus.brainbox.api.report.web.ReportGenerationRequestPayload
import com.afrithecus.brainbox.api.report.web.ReportHistoryItemPayload
import com.afrithecus.brainbox.api.report.web.ReportJobPayload
import com.afrithecus.brainbox.api.report.web.ReportJobStatus
import com.afrithecus.brainbox.api.report.web.ReportQuotaPayload
import com.afrithecus.brainbox.api.report.web.ReportType
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.util.UUID

/**
 * Reporting hub: idempotent async generation, polling, cancellation, paged
 * history and quota (docs/ongoing/api_reports_changes.md). The actor is always
 * derived from the bearer token; teacherId/teacherName in the body are ignored.
 */
@Service
class ReportGenerationService(
    private val jobs: ReportJobRepository,
    private val state: ReportJobStateService,
    private val runner: ReportJobRunner,
    private val renderer: ReportRenderer,
    private val schoolConfig: SchoolConfigService,
    private val data: ReportDataService,
    private val downloads: ReportDownloadService,
    private val userRepository: UserRepository,
    private val properties: ReportProperties,
    private val mapper: ObjectMapper,
) {

    fun submit(actor: CurrentUser, request: ReportGenerationRequestPayload, baseUrl: String?): ReportJobPayload {
        val user = requireCoordinator(actor)
        val schoolId = resolveSchool(user, request.schoolId)
        val requestId = request.jobRequestId?.trim()?.takeIf { it.isNotEmpty() } ?: UUID.randomUUID().toString()
        state.byRequest(user.id, requestId)?.let { return payload(it, baseUrl) }
        val created = createAndDispatch(user, schoolId, request, requestId)
        return payload(created, baseUrl)
    }

    @Transactional(readOnly = true)
    fun get(actor: CurrentUser, jobIdRaw: String, baseUrl: String?): ReportJobPayload {
        val job = state.find(parseJobId(jobIdRaw)) ?: throw notFound("Report job not found")
        requireJobAccess(actor, job)
        return payload(job, baseUrl)
    }

    fun cancel(actor: CurrentUser, jobIdRaw: String, baseUrl: String?): ReportJobPayload {
        val id = parseJobId(jobIdRaw)
        val job = state.find(id) ?: throw notFound("Report job not found")
        requireJobAccess(actor, job)
        val cancelled = state.cancel(id) ?: throw notFound("Report job not found")
        return payload(cancelled, baseUrl)
    }

    @Transactional(readOnly = true)
    fun history(actor: CurrentUser, limitRaw: Int?, beforeRaw: Long?, baseUrl: String?): List<ReportHistoryItemPayload> {
        val limit = (limitRaw ?: DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)
        val page = PageRequest.of(0, limit)
        val before = beforeRaw?.takeIf { it > 0 }?.let(java.time.Instant::ofEpochMilli)
        val rows = if (before == null) {
            jobs.findAllByOwnerIdAndStatusOrderByCreatedAtDesc(actor.userId, READY, page)
        } else {
            jobs.findAllByOwnerIdAndStatusAndCreatedAtLessThanOrderByCreatedAtDesc(actor.userId, READY, before, page)
        }
        return rows.mapNotNull { job ->
            val fileName = job.fileName ?: return@mapNotNull null
            if (job.storageName == null) return@mapNotNull null
            ReportHistoryItemPayload(
                id = job.id.toString(),
                reportType = ReportType.valueOf(job.reportType),
                title = job.title ?: fileName,
                fileName = fileName,
                fileUrl = downloadUrl(job, baseUrl) ?: return@mapNotNull null,
                fileSize = job.fileSize,
                classId = job.classId,
                term = job.term.orEmpty(),
                generatedAt = job.createdAt.toEpochMilli(),
                status = ReportJobStatus.valueOf(job.status),
            )
        }
    }

    fun quota(actor: CurrentUser): ReportQuotaPayload = downloads.quota(actor.userId)

    /**
     * Blank, branded template for the templates screen. The server is the renderer
     * of record (PDF-1 Option A), so the client no longer draws these on-device.
     */
    fun template(actor: CurrentUser, reportTypeRaw: String, schoolIdRaw: String?): RenderedTemplate {
        val user = requireCoordinator(actor)
        val reportType = runCatching { ReportType.valueOf(reportTypeRaw.trim().uppercase()) }.getOrNull()
            ?: throw invalidArgument("Unknown reportType: " + reportTypeRaw)
        val branding = ReportBranding.from(schoolConfig.branding(resolveSchool(user, schoolIdRaw)))
        val spec = TemplateSpec("Template - " + titleOf(reportType), branding, reportType)
        return RenderedTemplate(renderer.render(spec), "Template_" + reportType.name + ".pdf")
    }

    /** Creates and dispatches a job for a due schedule, owned by the schedule owner. */
    fun generateForSchedule(schedule: ReportScheduleEntity) {
        val owner = userRepository.findById(schedule.ownerId).orElse(null) ?: return
        val request = ReportGenerationRequestPayload(
            reportType = ReportType.valueOf(schedule.reportType),
            classId = schedule.classId,
            term = schedule.term.orEmpty(),
            year = java.time.LocalDate.now(java.time.ZoneOffset.UTC).year,
            jobRequestId = UUID.randomUUID().toString(),
            schoolId = schedule.schoolId?.toString(),
        )
        createAndDispatch(owner, schedule.schoolId, request, request.jobRequestId!!)
    }

    // ------------------------------------------------------------ internals

    private fun createAndDispatch(
        user: UserEntity,
        schoolId: UUID?,
        request: ReportGenerationRequestPayload,
        requestId: String,
    ): ReportJobEntity {
        var fresh = true
        val created = try {
            state.create(user.id, schoolId, request, requestId, titleOf(request.reportType), mapper.writeValueAsString(request))
        } catch (e: DataIntegrityViolationException) {
            fresh = false
            state.byRequest(user.id, requestId) ?: throw e
        }
        if (fresh) {
            val actor = CurrentUser(user.id, user.role, user.subRole)
            val estimate = runCatching { data.estimate(request, actor) }.getOrDefault(0)
            val threshold = if (isStudentType(request.reportType)) {
                properties.syncThresholdStudents
            } else {
                properties.syncThresholdRows
            }
            if (estimate <= threshold) runner.run(created.id) else runner.submit(created.id)
        }
        return state.find(created.id) ?: created
    }

    private fun payload(job: ReportJobEntity, baseUrl: String?): ReportJobPayload {
        val status = ReportJobStatus.valueOf(job.status)
        return ReportJobPayload(
            jobId = job.id.toString(),
            status = status,
            progress = job.progress,
            message = job.message,
            fileUrl = downloadUrl(job, baseUrl),
            fileName = job.fileName,
            fileSize = job.fileSize,
            createdAt = job.createdAt.toEpochMilli(),
            completedAt = job.completedAt?.toEpochMilli(),
            pollAfterMillis = if (status == ReportJobStatus.QUEUED || status == ReportJobStatus.IN_PROGRESS) {
                properties.pollHintMillis
            } else {
                null
            },
        )
    }

    private fun downloadUrl(job: ReportJobEntity, baseUrl: String?): String? {
        if (job.status != READY || job.storageName == null || baseUrl.isNullOrBlank()) return null
        return baseUrl.trimEnd('/') + "/teacher/reports/download/" + job.id + "?token=" + downloads.sign(job.id)
    }

    private fun requireCoordinator(actor: CurrentUser): UserEntity {
        val user = userRepository.findById(actor.userId).orElse(null) ?: throw notFound("User not found")
        val allowed = user.role == Role.ADMIN || (
            user.role == Role.TEACHER && (user.subRole == SubRole.GRADE_COORDINATOR || user.subRole == SubRole.ICT_ADMIN)
            )
        if (!allowed) throw ApiException(ApiErrorCode.FORBIDDEN, "Coordinator access required")
        return user
    }

    private fun requireJobAccess(actor: CurrentUser, job: ReportJobEntity) {
        if (job.ownerId == actor.userId) return
        val user = userRepository.findById(actor.userId).orElse(null) ?: throw notFound("User not found")
        val staff = user.role == Role.ADMIN || (
            user.role == Role.TEACHER && (user.subRole == SubRole.GRADE_COORDINATOR || user.subRole == SubRole.ICT_ADMIN)
            )
        if (!staff) throw ApiException(ApiErrorCode.FORBIDDEN, "Not your report")
        if (user.role != Role.ADMIN && (job.schoolId == null || job.schoolId != user.schoolId)) {
            throw ApiException(ApiErrorCode.FORBIDDEN, "Not your school's report")
        }
    }

    private fun resolveSchool(user: UserEntity, requested: String?): UUID? {
        if (requested.isNullOrBlank()) return user.schoolId
        val id = runCatching { UUID.fromString(requested.trim()) }.getOrNull()
            ?: throw invalidArgument("schoolId is not a valid identifier")
        if (user.role != Role.ADMIN && user.schoolId != id) {
            throw ApiException(ApiErrorCode.FORBIDDEN, "Not your school")
        }
        return id
    }

    private fun parseJobId(raw: String): UUID =
        runCatching { UUID.fromString(raw.trim()) }.getOrNull()
            ?: throw invalidArgument("jobId is not a valid identifier")

    private fun isStudentType(type: ReportType): Boolean = when (type) {
        ReportType.CBC_STUDENT, ReportType.DETAILED_CBC_STUDENT, ReportType.TRADITIONAL_STUDENT -> true
        else -> false
    }

    private fun titleOf(type: ReportType): String = when (type) {
        ReportType.CBC_CLASS -> "Class CBC Report"
        ReportType.DETAILED_CBC_CLASS -> "Detailed Class CBC Report"
        ReportType.CBC_STUDENT -> "Student CBC Report"
        ReportType.DETAILED_CBC_STUDENT -> "Detailed Student CBC Report"
        ReportType.TRADITIONAL_COMBINED -> "Combined Grade Report"
        ReportType.TRADITIONAL_STUDENT -> "Student Traditional Exam Report"
        ReportType.TRADITIONAL_PER_CLASS_TABLES -> "Per Class Tables"
        ReportType.TRADITIONAL_GRADE_ANALYSIS -> "Grade Analysis"
        ReportType.TEACHER_PERFORMANCE -> "Teacher Performance Report"
    }

    private companion object {
        const val READY = "READY"
        const val DEFAULT_LIMIT = 50
        const val MAX_LIMIT = 200
    }
}

/** Rendered bytes and suggested filename for a blank report template. */
data class RenderedTemplate(val bytes: ByteArray, val fileName: String)
