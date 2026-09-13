package com.afrithecus.brainbox.api.doubt.web

import com.afrithecus.brainbox.api.common.error.notFound
import com.afrithecus.brainbox.api.doubt.DoubtService
import com.afrithecus.brainbox.api.identity.model.CurrentUser
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import jakarta.validation.Valid
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/** Doubt solving forum (doc 05 §3). */
@RestController
@RequestMapping("/doubt")
class DoubtController(
    private val service: DoubtService,
    private val userRepository: UserRepository,
) {
    private fun user(current: CurrentUser) =
        userRepository.findById(current.userId).orElseThrow { notFound("User not found") }

    @GetMapping("/questions")
    fun list(
        @AuthenticationPrincipal currentUser: CurrentUser?,
        @RequestParam(required = false) subject: String?,
        @RequestParam(required = false) search: String?,
        @RequestParam(required = false) sort: String?,
    ): List<DoubtQuestionPayload> = service.list(currentUser?.userId, subject, search, sort)

    @GetMapping("/questions/{questionId}")
    fun detail(
        @AuthenticationPrincipal currentUser: CurrentUser?,
        @PathVariable questionId: String,
    ): DoubtQuestionPayload = service.detail(currentUser?.userId, questionId)

    @PostMapping("/questions")
    fun ask(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @Valid @RequestBody request: AskQuestionRequest,
    ): DoubtQuestionPayload = service.ask(user(currentUser), request)

    @PostMapping("/questions/{questionId}/bookmark")
    fun bookmark(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable questionId: String,
    ): DoubtQuestionPayload = service.bookmark(user(currentUser), questionId)

    @GetMapping("/questions/{questionId}/answers")
    fun answers(
        @AuthenticationPrincipal currentUser: CurrentUser?,
        @PathVariable questionId: String,
    ): List<DoubtAnswerPayload> = service.answers(currentUser?.userId, questionId)

    @PostMapping("/questions/{questionId}/answers")
    fun postAnswer(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable questionId: String,
        @Valid @RequestBody request: AnswerRequest,
    ): DoubtAnswerPayload = service.postAnswer(user(currentUser), questionId, request)

    @PostMapping("/answers/{answerId}/accept")
    fun accept(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable answerId: String,
    ): DoubtQuestionPayload = service.acceptAnswer(user(currentUser), answerId)

    @PostMapping("/questions/{questionId}/vote")
    fun voteQuestion(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable questionId: String,
        @RequestParam voteType: String,
    ): VoteResultPayload = service.vote(user(currentUser), "question", questionId, voteType)

    @PostMapping("/answers/{answerId}/vote")
    fun voteAnswer(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable answerId: String,
        @RequestParam voteType: String,
    ): VoteResultPayload = service.vote(user(currentUser), "answer", answerId, voteType)
}
