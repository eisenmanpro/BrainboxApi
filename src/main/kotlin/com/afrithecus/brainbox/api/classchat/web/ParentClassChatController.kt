package com.afrithecus.brainbox.api.classchat.web

import com.afrithecus.brainbox.api.classchat.ClassChatService
import com.afrithecus.brainbox.api.identity.model.CurrentUser
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile

/** Parent class-chat transport (docs/ongoing/api_class_group_chat_changes.md). */
@RestController
@RequestMapping("/parent")
@PreAuthorize("hasRole('PARENT')")
class ParentClassChatController(private val service: ClassChatService) {

    @GetMapping("/child/{childId}/class-groups")
    fun groups(
        @AuthenticationPrincipal current: CurrentUser,
        @PathVariable childId: String,
    ): List<ClassGroupPayload> = service.groupsForChild(current, childId)

    @GetMapping("/group/{groupId}/messages")
    fun messages(
        @AuthenticationPrincipal current: CurrentUser,
        @PathVariable groupId: String,
        @RequestParam(defaultValue = "50") limit: Int,
        @RequestParam(required = false) before: Long?,
    ): List<ClassGroupMessagePayload> = service.parentMessages(current, groupId, limit, before)

    @PostMapping("/group/{groupId}/messages")
    fun send(
        @AuthenticationPrincipal current: CurrentUser,
        @PathVariable groupId: String,
        @RequestParam text: String,
        @RequestParam(required = false) replyTo: String?,
        @RequestParam(defaultValue = "false") isAnnouncement: Boolean,
        @RequestParam(required = false) clientMessageId: String?,
        @RequestBody(required = false) attachments: List<MessageAttachmentPayload>?,
    ): ClassGroupMessagePayload = service.send(
        current,
        groupId,
        SendMessageRequest(text = text, attachments = attachments),
        replyTo,
        isAnnouncement,
        clientMessageId,
    )

    @PostMapping("/group/{groupId}/attachments")
    fun uploadAttachment(
        @PathVariable groupId: String,
        @RequestPart("file") file: MultipartFile,
    ): MessageAttachmentPayload = service.attachment(file)

    @PostMapping("/group/polls/{pollId}/vote")
    fun vote(
        @AuthenticationPrincipal current: CurrentUser,
        @PathVariable pollId: String,
        @RequestParam optionIndex: Int,
    ): ResponseEntity<Void> {
        service.votePoll(current, pollId, optionIndex)
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build()
    }

    @PostMapping("/group/{groupId}/read")
    fun markRead(
        @AuthenticationPrincipal current: CurrentUser,
        @PathVariable groupId: String,
    ): ResponseEntity<Void> {
        service.markRead(current, groupId)
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build()
    }
}
