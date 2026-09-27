package com.afrithecus.brainbox.api.parent.web

import com.afrithecus.brainbox.api.identity.model.CurrentUser
import com.afrithecus.brainbox.api.parent.ParentMessagesService
import com.afrithecus.brainbox.api.parent.ParentPortalService
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * The parent portal: which learners a guardian may see, and how they link one.
 *
 * The app has always called `GET parent/children`; it is served here from the
 * `users.parent_user_id` link the rest of the parent surfaces already use
 * (`api_parent_portal_changes.md`).
 */
@RestController
@RequestMapping("/parent")
class ParentPortalController(
    private val service: ParentPortalService,
    private val messages: ParentMessagesService,
) {

    @GetMapping("/children")
    fun children(@AuthenticationPrincipal current: CurrentUser): List<LinkedChildPayload> =
        service.children(current)

    @PostMapping("/child/link")
    fun linkChild(
        @AuthenticationPrincipal current: CurrentUser,
        @RequestBody request: LinkChildRequest,
    ): LinkedChildPayload = service.linkChild(current, request)

    @GetMapping("/messages/{childId}")
    fun messages(
        @AuthenticationPrincipal current: CurrentUser,
        @PathVariable childId: String,
    ): List<ParentMessagePayload> = messages.messages(current, childId)

    @PostMapping("/message")
    fun sendMessage(
        @AuthenticationPrincipal current: CurrentUser,
        @RequestBody request: SendParentMessageRequest,
    ): ParentMessagePayload = messages.send(current, request)

    @DeleteMapping("/child/{childId}")
    fun unlinkChild(
        @AuthenticationPrincipal current: CurrentUser,
        @PathVariable childId: String,
    ): ResponseEntity<Void> {
        service.unlinkChild(current, childId)
        return ResponseEntity.noContent().build()
    }
}
