package com.afrithecus.brainbox.api.traditional.web

import com.afrithecus.brainbox.api.identity.model.CurrentUser
import com.afrithecus.brainbox.api.traditional.TraditionalExamService
import com.afrithecus.brainbox.api.traditional.model.ExamTerm
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * Traditional (paper) exam engine (docs 10/13). Every path mirrors
 * network/traditional/TraditionalExamApi.kt in the Android app; authorization and
 * scoping live in [TraditionalExamService].
 */
@RestController
@RequestMapping("/traditional")
class TraditionalExamController(
    private val service: TraditionalExamService,
) {

    // ---------------------------------------------------------------- exams

    @GetMapping("/exams")
    fun listExams(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @RequestParam(required = false) grade: String?,
    ): List<TraditionalExamDto> = service.listExams(currentUser, grade)

    @GetMapping("/exams/{examId}")
    fun getExam(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable examId: String,
    ): TraditionalExamDto = service.getExam(currentUser, examId)

    @PostMapping("/exams")
    fun createExam(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @Valid @RequestBody request: CreateTraditionalExamRequest,
    ): TraditionalExamDto = service.createExam(currentUser, request)

    @PostMapping("/exams/generate")
    fun generateExams(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @RequestParam grade: String,
        @RequestParam term: ExamTerm,
        @RequestParam year: Int,
        @RequestBody subjects: List<SubjectConfigDto>,
    ): List<TraditionalExamDto> = service.generateExams(currentUser, grade, term, year, subjects)

    @GetMapping("/exams/{examId}/confirmation-status")
    fun confirmationStatus(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable examId: String,
    ): ExamConfirmationStatusDto = service.confirmationStatus(currentUser, examId)

    @PostMapping("/exams/{examId}/status/{status}")
    fun updateStatus(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable examId: String,
        @PathVariable status: String,
    ): TraditionalExamDto = service.updateStatus(currentUser, examId, status)

    @PostMapping("/exams/{examId}/pre-final")
    fun advanceToPreFinal(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable examId: String,
    ): TraditionalExamDto = service.advanceToPreFinal(currentUser, examId)

    @PostMapping("/exams/{examId}/finalize")
    fun finalizeExam(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable examId: String,
        @RequestParam(required = false) coordinatorId: String?,
        @RequestParam(required = false) remarks: String?,
    ): TraditionalExamDto = service.finalize(currentUser, examId, coordinatorId, remarks)

    @PostMapping("/exams/{examId}/publish")
    fun publishResults(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable examId: String,
        @RequestParam(required = false) coordinatorId: String?,
    ): TraditionalExamDto = service.publish(currentUser, examId, coordinatorId)

    @GetMapping("/exams/{examId}/pre-final-checks")
    fun getPreFinalChecks(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable examId: String,
    ): PreFinalCheckSummaryDto = service.preFinalChecks(currentUser, examId)

    @PostMapping("/exams/{examId}/remind/{teacherId}")
    fun remindTeacher(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable examId: String,
        @PathVariable teacherId: String,
    ): ResponseEntity<Void> {
        service.remindTeacher(currentUser, examId, teacherId)
        return ResponseEntity.noContent().build()
    }

    // ---------------------------------------------------------------- edit requests

    @GetMapping("/exams/{examId}/edit-requests")
    fun getEditRequests(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable examId: String,
    ): List<EditRequestDto> = service.editRequests(currentUser, examId)

    @PostMapping("/exams/{examId}/edit-requests")
    fun requestEdit(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable examId: String,
        @RequestBody request: EditRequestDto,
    ): EditRequestDto = service.requestEdit(currentUser, examId, request)

    @PostMapping("/edit-requests/{requestId}/approve")
    fun approveEditRequest(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable requestId: String,
        @RequestParam(required = false) coordinatorId: String?,
    ): EditRequestDto = service.approveEditRequest(currentUser, requestId, coordinatorId)

    @PostMapping("/edit-requests/{requestId}/deny")
    fun denyEditRequest(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable requestId: String,
        @RequestParam(required = false) coordinatorId: String?,
        @RequestParam(required = false) reason: String?,
    ): EditRequestDto = service.denyEditRequest(currentUser, requestId, coordinatorId, reason)

    @PostMapping("/exams/{examId}/edit-permission/check")
    fun checkEditPermission(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable examId: String,
        @RequestParam studentId: String,
        @RequestParam teacherId: String,
    ): Boolean = service.checkEditPermission(currentUser, examId, studentId, teacherId)

    @PostMapping("/exams/{examId}/edit-permission/grant")
    fun grantEditPermission(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable examId: String,
        @RequestBody permission: EditPermissionDto,
    ): ResponseEntity<Void> {
        service.grantEditPermission(currentUser, examId, permission)
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/exams/{examId}/edit-permission/consume")
    fun consumeEditPermission(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable examId: String,
        @RequestParam studentId: String,
        @RequestParam teacherId: String,
    ): ResponseEntity<Void> {
        service.consumeEditPermission(currentUser, examId, studentId, teacherId)
        return ResponseEntity.noContent().build()
    }

    // ---------------------------------------------------------------- marks / students

    @GetMapping("/teachers/{teacherId}/class")
    fun teacherClass(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable teacherId: String,
    ): ResponseEntity<String> {
        val classTag = service.teacherClass(currentUser, teacherId)
        // A teacher with no active class is a legitimate null; return 204 instead of
        // an empty 200 body that the client's String adapter cannot represent.
        return if (classTag.isNullOrBlank()) ResponseEntity.noContent().build() else ResponseEntity.ok(classTag)
    }

    @GetMapping("/exams/{examId}/subjects")
    fun examSubjects(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable examId: String,
    ): List<SubjectConfigDto> = service.examSubjects(currentUser, examId)

    @GetMapping("/exams/{examId}/students")
    fun students(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable examId: String,
        @RequestParam(required = false) grade: String?,
    ): List<StudentDto> = service.students(currentUser, examId, grade)

    @GetMapping("/exams/{examId}/marks")
    fun marks(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable examId: String,
    ): List<TraditionalMarkDto> = service.marks(currentUser, examId)

    @PostMapping("/exams/{examId}/marks")
    fun saveMarks(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable examId: String,
        @RequestBody entries: List<MarkEntryDto>,
    ): List<TraditionalMarkDto> = service.saveMarks(currentUser, examId, entries)

    @PostMapping("/exams/{examId}/confirm")
    fun confirmMarks(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable examId: String,
        @RequestParam(required = false) grade: String?,
    ): ResponseEntity<Void> {
        service.confirmMarks(currentUser, examId, grade)
        return ResponseEntity.noContent().build()
    }

    // ---------------------------------------------------------------- results / reports

    @GetMapping("/exams/{examId}/results/me")
    fun myResult(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable examId: String,
        @RequestParam(required = false) studentId: String?,
    ): TraditionalStudentReportDto = service.myResult(currentUser, examId, studentId)

    @GetMapping("/exams/{examId}/analytics")
    fun analytics(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable examId: String,
    ): TraditionalExamAnalyticsDto = service.analytics(currentUser, examId)

    @GetMapping("/exams/{examId}/grade-analysis")
    fun gradeAnalysis(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable examId: String,
    ): TraditionalGradeAnalysisDto = service.gradeAnalysis(currentUser, examId)

    @GetMapping("/exams/{examId}/grade-wide-ranking")
    fun gradeWideRanking(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable examId: String,
    ): List<StudentGradeRowDto> = service.gradeWideRanking(currentUser, examId)

    @GetMapping("/exams/{examId}/per-class-rankings")
    fun perClassRankings(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable examId: String,
    ): Map<String, List<StudentGradeRowDto>> = service.perClassRankings(currentUser, examId)

    // ---------------------------------------------------------------- grade config

    @GetMapping("/grades/{gradeLevel}/subjects")
    fun gradeSubjectConfig(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable gradeLevel: String,
        @RequestParam(required = false) schoolId: String?,
    ): List<SubjectConfigDto>? = service.gradeSubjectConfig(currentUser, gradeLevel, schoolId)

    @PostMapping("/grades/{gradeLevel}/subjects")
    fun saveGradeSubjectConfig(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable gradeLevel: String,
        @RequestParam(required = false) schoolId: String?,
        @RequestBody subjects: List<SubjectConfigDto>,
    ): ResponseEntity<Void> {
        service.saveGradeSubjectConfig(currentUser, gradeLevel, schoolId, subjects)
        return ResponseEntity.noContent().build()
    }

    @GetMapping("/grades/{gradeLevel}/grading")
    fun gradingConfig(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable gradeLevel: String,
        @RequestParam(required = false) schoolId: String?,
    ): GradingConfigDto = service.gradingConfig(currentUser, gradeLevel, schoolId)

    @PostMapping("/grades/{gradeLevel}/grading")
    fun saveGradingConfig(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable gradeLevel: String,
        @RequestParam(required = false) schoolId: String?,
        @RequestBody config: GradingConfigDto,
    ): ResponseEntity<Void> {
        service.saveGradingConfig(currentUser, gradeLevel, schoolId, config)
        return ResponseEntity.noContent().build()
    }
}
