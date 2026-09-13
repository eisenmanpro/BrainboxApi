package com.afrithecus.brainbox.api.feedback.web

import com.afrithecus.brainbox.api.common.error.notFound
import com.afrithecus.brainbox.api.feedback.FeedbackService
import com.afrithecus.brainbox.api.identity.model.CurrentUser
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/** Teacher feedback (docs/ongoing; doc 04 section 7). */
@RestController
@RequestMapping("/teacher/feedback")
class FeedbackController(
    private val service: FeedbackService,
    private val userRepository: UserRepository,
) {
    private fun teacher(current: CurrentUser) =
        userRepository.findById(current.userId).orElseThrow { notFound("User not found") }

    @GetMapping("/templates")
    fun templates(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @RequestParam(required = false) teacherId: String?,
    ): List<FeedbackTemplatePayload> = service.templates(teacher(currentUser))

    @PostMapping("/templates")
    fun saveTemplate(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @RequestBody template: FeedbackTemplatePayload,
    ): FeedbackTemplatePayload = service.saveTemplate(teacher(currentUser), template)

    @DeleteMapping("/templates/{templateId}")
    fun deleteTemplate(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable templateId: String,
    ): ResponseEntity<Void> {
        service.deleteTemplate(teacher(currentUser), templateId)
        return ResponseEntity.noContent().build()
    }

    @GetMapping("/history")
    fun history(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @RequestParam(required = false) teacherId: String?,
        @RequestParam(required = false) studentId: String?,
    ): List<TeacherFeedbackPayload> = service.history(teacher(currentUser), studentId)

    @PostMapping
    fun submit(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @RequestBody feedback: TeacherFeedbackPayload,
    ): TeacherFeedbackPayload = service.submit(teacher(currentUser), feedback)

    @PostMapping("/bulk")
    fun submitBulk(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @RequestBody feedbackList: List<TeacherFeedbackPayload>,
    ): List<TeacherFeedbackPayload> = service.submitBulk(teacher(currentUser), feedbackList)
}
