package com.afrithecus.brainbox.api.exams.web

import com.afrithecus.brainbox.api.common.error.notFound
import com.afrithecus.brainbox.api.exams.TeacherExamAnalysisService
import com.afrithecus.brainbox.api.exams.TeacherExamService
import com.afrithecus.brainbox.api.identity.model.CurrentUser
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** Teacher digital exam authoring and analysis (docs/ongoing/api_exams_changes.md). */
@RestController
@RequestMapping("/teacher/exams")
class TeacherExamController(
    private val service: TeacherExamService,
    private val analysis: TeacherExamAnalysisService,
    private val userRepository: UserRepository,
) {
    private fun teacher(current: CurrentUser) =
        userRepository.findById(current.userId).orElseThrow { notFound("User not found") }

    @GetMapping
    fun list(@AuthenticationPrincipal currentUser: CurrentUser): List<TeacherExamPayload> =
        service.list(teacher(currentUser))

    @PostMapping
    fun create(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @RequestBody request: TeacherExamPayload,
    ): TeacherExamPayload = service.create(teacher(currentUser), request)

    @PutMapping("/{examId}")
    fun update(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable examId: String,
        @RequestBody request: TeacherExamPayload,
    ): TeacherExamPayload = service.update(teacher(currentUser), examId, request)

    @DeleteMapping("/{examId}")
    fun delete(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable examId: String,
    ): ResponseEntity<Void> {
        service.delete(teacher(currentUser), examId)
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/{examId}/publish")
    fun publish(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable examId: String,
    ): TeacherExamPayload = service.publish(teacher(currentUser), examId)

    @GetMapping("/review-queue")
    fun reviewQueue(
        @AuthenticationPrincipal currentUser: CurrentUser,
    ): List<TeacherReviewQueueItemPayload> = analysis.reviewQueue(teacher(currentUser))

    @PostMapping("/review/{submissionId}/{questionId}")
    fun reviewMark(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable submissionId: String,
        @PathVariable questionId: String,
        @RequestBody body: ReviewMarkPayload,
    ): ResponseEntity<Void> {
        analysis.submitReviewMark(teacher(currentUser), submissionId, questionId, body.mark)
        return ResponseEntity.noContent().build()
    }

    @GetMapping("/{examId}/analysis")
    fun analysis(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable examId: String,
    ): ExamAnalysisReportPayload = analysis.analysis(teacher(currentUser), examId)

    @GetMapping("/{examId}/remediation")
    fun remediation(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable examId: String,
    ): List<RemediationAssignmentPayload> = analysis.remediation(teacher(currentUser), examId)

    @PostMapping("/{examId}/remediation")
    fun saveRemediation(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable examId: String,
        @RequestBody body: RemediationSavePayload,
    ): ResponseEntity<Void> {
        analysis.saveRemediations(teacher(currentUser), examId, body)
        return ResponseEntity.noContent().build()
    }
}

/** Reusable teacher question bank (docs/ongoing/api_exams_changes.md). */
@RestController
@RequestMapping("/teacher/question-bank")
class TeacherQuestionBankController(
    private val service: TeacherExamService,
    private val userRepository: UserRepository,
) {
    private fun teacher(current: CurrentUser) =
        userRepository.findById(current.userId).orElseThrow { notFound("User not found") }

    @GetMapping
    fun bank(@AuthenticationPrincipal currentUser: CurrentUser): List<TeacherQuestionPayload> =
        service.questionBank(teacher(currentUser))

    @PostMapping
    fun add(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @RequestBody question: TeacherQuestionPayload,
    ): ResponseEntity<Void> {
        service.addToQuestionBank(teacher(currentUser), question)
        return ResponseEntity.noContent().build()
    }
}
