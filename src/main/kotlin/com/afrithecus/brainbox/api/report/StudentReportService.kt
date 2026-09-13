package com.afrithecus.brainbox.api.report

import com.afrithecus.brainbox.api.classes.repository.ClassMembershipRepository
import com.afrithecus.brainbox.api.classes.repository.TeacherClassRepository
import com.afrithecus.brainbox.api.common.error.ApiErrorCode
import com.afrithecus.brainbox.api.common.error.ApiException
import com.afrithecus.brainbox.api.common.error.invalidArgument
import com.afrithecus.brainbox.api.common.error.notFound
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.model.CurrentUser
import com.afrithecus.brainbox.api.identity.model.Role
import com.afrithecus.brainbox.api.identity.model.SubRole
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import com.afrithecus.brainbox.api.report.web.ReportGenerationRequestPayload
import com.afrithecus.brainbox.api.report.web.ReportJobPayload
import com.afrithecus.brainbox.api.report.web.ReportType
import com.afrithecus.brainbox.api.report.web.StudentReportRequestPayload
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Learner/parent-facing report requests (docs/ongoing/pdf_generator_cleanup.md section 3).
 * The report hub is coordinator-only, so this resolves the viewer's own or linked
 * learner and submits a report the viewer owns; the server remains the renderer of
 * record. Only single-learner, non-sensitive report types are exposed.
 */
@Service
class StudentReportService(
    private val generation: ReportGenerationService,
    private val userRepository: UserRepository,
    private val membershipRepository: ClassMembershipRepository,
    private val classRepository: TeacherClassRepository,
) {

    fun generate(actor: CurrentUser, request: StudentReportRequestPayload, baseUrl: String?): ReportJobPayload {
        val viewer = userRepository.findById(actor.userId).orElse(null) ?: throw notFound("User not found")
        if (request.reportType !in VIEWER_TYPES) {
            throw invalidArgument("reportType is not available to learners")
        }
        val student = resolveStudent(viewer, request.studentId)
        val payload = ReportGenerationRequestPayload(
            reportType = request.reportType,
            studentIds = listOf(student.id.toString()),
            term = request.term,
            year = request.year,
            examId = if (request.reportType == ReportType.TRADITIONAL_STUDENT) {
                request.examId?.takeIf { it.isNotBlank() }
                    ?: throw invalidArgument("examId is required for a traditional student report")
            } else {
                null
            },
            jobRequestId = request.jobRequestId,
            schoolId = (student.schoolId ?: viewer.schoolId)?.toString(),
        )
        return generation.submitStudent(actor, student, payload, baseUrl)
    }

    private fun resolveStudent(viewer: UserEntity, requestedRaw: String?): UserEntity {
        val requested = requestedRaw?.takeIf { it.isNotBlank() }?.let {
            runCatching { UUID.fromString(it.trim()) }.getOrNull()
                ?: throw invalidArgument("studentId is not a valid identifier")
        }
        return when (viewer.role) {
            Role.STUDENT -> {
                if (requested != null && requested != viewer.id) throw forbidden("Cannot request another learner's report")
                viewer
            }
            Role.PARENT -> {
                val childId = requested ?: userRepository.findByParentUserId(viewer.id).firstOrNull()?.id
                    ?: throw notFound("No linked child")
                val child = userRepository.findById(childId).orElse(null) ?: throw notFound("Learner not found")
                if (child.parentUserId != viewer.id) throw forbidden("Not your linked child")
                child
            }
            Role.TEACHER -> {
                val student = requested?.let { userRepository.findById(it).orElse(null) }
                    ?: throw invalidArgument("studentId is required")
                if (!isCoordinator(viewer) && !sharesClass(viewer, student)) throw forbidden("Not your learner")
                student
            }
            Role.ADMIN -> requested?.let { userRepository.findById(it).orElse(null) }
                ?: throw invalidArgument("studentId is required")
            else -> throw forbidden("Learner report access required")
        }
    }

    private fun isCoordinator(viewer: UserEntity): Boolean =
        viewer.subRole == SubRole.GRADE_COORDINATOR || viewer.subRole == SubRole.ICT_ADMIN

    private fun sharesClass(viewer: UserEntity, student: UserEntity): Boolean {
        val studentClasses = membershipRepository.findAllByStudentId(student.id).map { it.classId }.toSet()
        val teacherClasses = classRepository.findAllByTeacherUserIdAndIsActiveTrueOrderByNameAsc(viewer.id)
            .map { it.id }
            .toSet()
        return studentClasses.intersect(teacherClasses).isNotEmpty()
    }

    private fun forbidden(message: String) = ApiException(ApiErrorCode.FORBIDDEN, message)

    private companion object {
        /** Report types safe for a learner, parent or teacher-of-record to request. */
        val VIEWER_TYPES = setOf(
            ReportType.CBC_STUDENT,
            ReportType.DETAILED_CBC_STUDENT,
            ReportType.TRADITIONAL_STUDENT,
        )
    }
}
