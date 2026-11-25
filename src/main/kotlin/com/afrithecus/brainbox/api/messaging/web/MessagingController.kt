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

    /**
     * Who the caller may message, with the relationship that justifies each row. Optional
     * role/grade/class/search filters. Literal paths are declared above the /{userId} route so
     * Spring can never bind "recipients" as a user id.
     */
    @GetMapping("/recipients")
    fun recipients(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @RequestParam(required = false) role: String?,
        @RequestParam(required = false) gradeLevel: String?,
        @RequestParam(required = false) classId: String?,
        @RequestParam(required = false) q: String?,
        /** ADMIN/ICT_ADMIN only: list ICT admins of the other schools (`OTHER_SCHOOL_ADMINS`). */
        @RequestParam(required = false) scope: String?,
    ): List<MessageRecipientPayload> = service.recipients(user(currentUser), role, gradeLevel, classId, q, scope)

    /** One conversation (oldest first) the caller participates in — the thread view's read. */
    @GetMapping("/threads/{threadId}")
    fun thread(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable threadId: String,
    ): List<MessagePayload> = service.thread(user(currentUser), threadId)

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

    /** Archive/restore the caller's own copy (server-persisted; survives reinstall and devices). */
    @PostMapping("/{messageId}/archive")
    fun archive(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable messageId: String,
        @RequestBody body: MessageArchiveRequest,
    ): ResponseEntity<Void> {
        service.setArchived(user(currentUser), messageId, body.archived)
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build()
    }

    /** Flag/unflag the caller's own copy. */
    @PostMapping("/{messageId}/flag")
    fun flag(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable messageId: String,
        @RequestBody body: MessageFlagRequest,
    ): ResponseEntity<Void> {
        service.setFlagged(user(currentUser), messageId, body.flagged)
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build()
    }

    private fun requireSelf(current: CurrentUser, userId: String): java.util.UUID {
        if (userId != current.userId.toString()) {
            throw ApiException(ApiErrorCode.FORBIDDEN, "Cannot read another user's messages")
        }
        return current.userId
    }
}
