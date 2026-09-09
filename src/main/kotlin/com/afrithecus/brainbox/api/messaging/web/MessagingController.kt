package com.afrithecus.brainbox.api.messaging.web

import com.afrithecus.brainbox.api.common.error.ApiErrorCode
import com.afrithecus.brainbox.api.common.error.ApiException
import com.afrithecus.brainbox.api.common.error.notFound
import com.afrithecus.brainbox.api.identity.model.CurrentUser
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import com.afrithecus.brainbox.api.messaging.MessagingService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/** Messaging (doc 05 §2). */
@RestController
@RequestMapping("/messages")
class MessagingController(
    private val service: MessagingService,
    private val userRepository: UserRepository,
) {
    private fun user(current: CurrentUser) =
        userRepository.findById(current.userId).orElseThrow { notFound("User not found") }

    @GetMapping("/{userId}")
    fun list(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable userId: String,
        @RequestParam(defaultValue = "inbox") folder: String,
    ): List<MessagePayload> {
        if (userId != currentUser.userId.toString()) {
            throw ApiException(ApiErrorCode.FORBIDDEN, "Cannot read another user's messages")
        }
        return service.list(currentUser.userId, folder)
    }

    @GetMapping("/{userId}/sent")
    fun sent(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable userId: String,
    ): List<MessagePayload> = service.list(requireSelf(currentUser, userId), "sent")

    @GetMapping("/{userId}/outbox")
    fun outbox(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable userId: String,
    ): List<MessagePayload> = service.list(requireSelf(currentUser, userId), "outbox")

    @PostMapping("/send")
    fun send(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @RequestHeader(value = "X-Message-Id", required = false) messageId: String?,
        @Valid @RequestBody request: SendMessageRequest,
    ): MessagePayload = service.send(user(currentUser), request, messageId)

    @PostMapping("/{messageId}/read")
    fun markRead(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable messageId: String,
    ): ResponseEntity<Void> {
        service.markRead(user(currentUser), messageId)
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build()
    }

    private fun requireSelf(current: CurrentUser, userId: String): java.util.UUID {
        if (userId != current.userId.toString()) {
            throw ApiException(ApiErrorCode.FORBIDDEN, "Cannot read another user's messages")
        }
        return current.userId
    }
}
