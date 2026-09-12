package com.afrithecus.brainbox.api.classchat.web

import com.afrithecus.brainbox.api.classchat.ClassChatService
import com.afrithecus.brainbox.api.identity.model.CurrentUser
import com.afrithecus.brainbox.api.live.web.LivePollPayload
import com.afrithecus.brainbox.api.media.MediaService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile

/**
 * Teacher class-group chat (doc 04 §12.1-12.4). Realtime delivery is Phase 6;
 * this REST surface is what the app uses today.
 */
@RestController
@RequestMapping("/teacher/class-groups")
@PreAuthorize("hasAnyRole('TEACHER','CTEACHER','GRADE_COORDINATOR','ICT_ADMIN')")
class ClassChatController(
    private val service: ClassChatService,
    private val mediaService: MediaService,
) {

    @GetMapping
    fun groups(
        @AuthenticationPrincipal current: CurrentUser,
        @RequestParam teacherId: String,
    ): List<ClassGroupPayload> = service.groups(current, teacherId)

    @PostMapping
    fun create(
        @AuthenticationPrincipal current: CurrentUser,
        @RequestParam teacherId: String,
        @RequestParam name: String,
        @RequestParam classId: String,
        @RequestBody(required = false) memberIds: List<String>?,
    ): ClassGroupPayload = service.create(current, teacherId, name, classId, memberIds.orEmpty())

    @PutMapping("/{groupId}")
    fun update(
        @AuthenticationPrincipal current: CurrentUser,
        @PathVariable groupId: String,
        @RequestParam(required = false) name: String?,
        @RequestParam(required = false) description: String?,
        @RequestBody(required = false) memberIds: List<String>?,
    ): ClassGroupPayload = service.update(current, groupId, name, description, memberIds)

    @DeleteMapping("/{groupId}")
    fun delete(
        @AuthenticationPrincipal current: CurrentUser,
        @PathVariable groupId: String,
    ): ResponseEntity<Void> {
        service.delete(current, groupId)
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build()
    }

    @GetMapping("/{groupId}/messages")
    fun messages(
        @AuthenticationPrincipal current: CurrentUser,
        @PathVariable groupId: String,
        @RequestParam(defaultValue = "50") limit: Int,
        @RequestParam(required = false) before: Long?,
    ): List<ClassGroupMessagePayload> = service.messages(current, groupId, limit, before)

    @PostMapping("/{groupId}/messages")
    fun send(
        @AuthenticationPrincipal current: CurrentUser,
        @PathVariable groupId: String,
        @RequestParam text: String,
        @RequestParam(required = false) replyTo: String?,
        @RequestParam(defaultValue = "false") isAnnouncement: Boolean,
        @RequestBody(required = false) attachments: List<MessageAttachmentPayload>?,
    ): ClassGroupMessagePayload =
        service.send(current, groupId, SendMessageRequest(text = text, attachments = attachments), replyTo, isAnnouncement)

    @PostMapping("/{groupId}/messages/{messageId}/pin")
    fun pin(
        @AuthenticationPrincipal current: CurrentUser,
        @PathVariable groupId: String,
        @PathVariable messageId: String,
    ): ResponseEntity<Void> {
        service.setPinned(current, groupId, messageId, true)
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build()
    }

    @PostMapping("/{groupId}/messages/{messageId}/unpin")
    fun unpin(
        @AuthenticationPrincipal current: CurrentUser,
        @PathVariable groupId: String,
        @PathVariable messageId: String,
    ): ResponseEntity<Void> {
        service.setPinned(current, groupId, messageId, false)
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build()
    }

    @DeleteMapping("/{groupId}/messages/{messageId}")
    fun deleteMessage(
        @AuthenticationPrincipal current: CurrentUser,
        @PathVariable groupId: String,
        @PathVariable messageId: String,
    ): ResponseEntity<Void> {
        service.deleteMessage(current, groupId, messageId)
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build()
    }

    @PostMapping("/{groupId}/members/{memberId}/mute")
    fun muteMember(
        @AuthenticationPrincipal current: CurrentUser,
        @PathVariable groupId: String,
        @PathVariable memberId: String,
        @RequestParam durationMinutes: Int,
    ): ResponseEntity<Void> {
        service.muteMember(current, groupId, memberId, durationMinutes)
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build()
    }

    @PostMapping("/{groupId}/polls")
    fun createPoll(
        @AuthenticationPrincipal current: CurrentUser,
        @PathVariable groupId: String,
        @RequestParam question: String,
        @RequestBody options: List<String>,
    ): LivePollPayload = service.createPoll(current, groupId, question, options)

    @GetMapping("/{groupId}/gradebook")
    fun gradebook(
        @AuthenticationPrincipal current: CurrentUser,
        @PathVariable groupId: String,
    ): List<GradebookContributionPayload> = service.gradebookContributions(current, groupId)

    @GetMapping("/{groupId}/teachers")
    fun teachers(
        @AuthenticationPrincipal current: CurrentUser,
        @PathVariable groupId: String,
    ): List<GroupTeacherPayload> = service.teachers(current, groupId)

    @PostMapping("/{groupId}/attachments")
    fun uploadAttachment(
        @PathVariable groupId: String,
        @RequestPart("file") file: MultipartFile,
    ): MessageAttachmentPayload {
        val stored = mediaService.store(file, allowDocuments = true)
        return MessageAttachmentPayload(
            url = stored.url,
            type = attachmentType(file.contentType ?: "", stored.mediaType),
            fileName = file.originalFilename,
            fileSize = file.size,
        )
    }

    private fun attachmentType(contentType: String, mediaType: String): String = when {
        mediaType == "IMAGE" -> "IMAGE"
        contentType == "application/pdf" -> "PDF"
        contentType.startsWith("audio/") -> "AUDIO"
        else -> "FILE"
    }
}

/** Poll voting lives on its own path (doc 04 §12.4). */
@RestController
@RequestMapping("/teacher/polls")
@PreAuthorize("hasAnyRole('TEACHER','CTEACHER','GRADE_COORDINATOR','ICT_ADMIN')")
class ClassChatPollController(private val service: ClassChatService) {

    @PostMapping("/{pollId}/vote")
    fun vote(
        @AuthenticationPrincipal current: CurrentUser,
        @PathVariable pollId: String,
        @RequestParam optionIndex: Int,
    ): ResponseEntity<Void> {
        service.votePoll(current, pollId, optionIndex)
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build()
    }
}
