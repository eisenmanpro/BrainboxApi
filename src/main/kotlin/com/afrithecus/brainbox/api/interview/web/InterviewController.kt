package com.afrithecus.brainbox.api.interview.web

import com.afrithecus.brainbox.api.identity.model.CurrentUser
import com.afrithecus.brainbox.api.interview.InterviewService
import jakarta.validation.Valid
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/** Mock interviews (doc 06 §2). */
@RestController
@RequestMapping("/interview")
class InterviewController(private val service: InterviewService) {

    @GetMapping("/questions")
    fun questions(
        @RequestParam type: String,
        @RequestParam(defaultValue = "1") difficulty: Int,
    ): List<InterviewQuestionPayload> = service.questions(type, difficulty)

    @PostMapping("/start")
    fun start(
        @AuthenticationPrincipal current: CurrentUser,
        @Valid @RequestBody request: StartInterviewRequest,
    ): InterviewSessionPayload = service.start(current, request)

    @PostMapping("/submit-answer")
    fun submitAnswer(
        @AuthenticationPrincipal current: CurrentUser,
        @Valid @RequestBody request: SubmitAnswerRequest,
    ): InterviewAnswerPayload = service.submitAnswer(current, request)

    @PostMapping("/complete")
    fun complete(
        @AuthenticationPrincipal current: CurrentUser,
        @RequestParam sessionId: String,
    ): InterviewResultPayload = service.complete(current, sessionId)

    @GetMapping("/history/{userId}")
    fun history(
        @AuthenticationPrincipal current: CurrentUser,
        @PathVariable userId: String,
    ): List<PastAttemptPayload> = service.history(current, userId)

    @GetMapping("/analytics/{userId}")
    fun analytics(
        @AuthenticationPrincipal current: CurrentUser,
        @PathVariable userId: String,
    ): InterviewAnalyticsPayload = service.analytics(current, userId)
}
