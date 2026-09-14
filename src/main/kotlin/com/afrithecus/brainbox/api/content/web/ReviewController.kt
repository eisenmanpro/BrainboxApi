package com.afrithecus.brainbox.api.content.web

import com.afrithecus.brainbox.api.common.error.invalidArgument
import com.afrithecus.brainbox.api.common.error.notFound
import com.afrithecus.brainbox.api.content.ReviewSurfaceService
import com.afrithecus.brainbox.api.identity.model.CurrentUser
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Phase 7.4b moderation review surface for the teacher workspace. Only teachers,
 * coordinators, CTEs and ICT admins may read the queue or record a decision; a
 * decision here is what moves content from UNREVIEWED to learner-visible.
 */
@RestController
@RequestMapping("/teacher/review")
@PreAuthorize("hasAnyRole('TEACHER','CTEACHER','GRADE_COORDINATOR','ICT_ADMIN')")
class ReviewController(
    private val service: ReviewSurfaceService,
    private val users: UserRepository,
) {

    @GetMapping("/queue")
    fun queue(
        @RequestParam(required = false) state: String?,
        @RequestParam(required = false) subject: String?,
        @RequestParam(required = false) grade: String?,
        @RequestParam(required = false, defaultValue = "20") limit: Int,
        @RequestParam(required = false) cursor: String?,
    ): ReviewQueuePayload = service.queue(state, subject, grade, limit, cursor)

    @GetMapping("/{contentType}/{contentId}")
    fun item(
        @PathVariable contentType: String,
        @PathVariable contentId: String,
        @RequestParam(required = false, defaultValue = "1") contentVersion: Int,
    ): ReviewItemPayload = service.item(contentType, uuid(contentId), contentVersion)

    @PostMapping("/{contentType}/{contentId}/decision")
    fun decide(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable contentType: String,
        @PathVariable contentId: String,
        @RequestBody request: ReviewDecisionRequest,
    ): ModerationOutcomePayload = service.decide(actor(currentUser), contentType, uuid(contentId), request)

    @GetMapping("/{contentType}/{contentId}/decisions")
    fun decisions(
        @PathVariable contentType: String,
        @PathVariable contentId: String,
        @RequestParam(required = false, defaultValue = "1") contentVersion: Int,
    ): List<ContentReviewPayload> = service.decisions(contentType, uuid(contentId), contentVersion)

    private fun actor(currentUser: CurrentUser) =
        users.findById(currentUser.userId).orElseThrow { notFound("User not found") }

    private fun uuid(raw: String): UUID =
        runCatching { UUID.fromString(raw) }.getOrElse { throw invalidArgument("contentId is not a UUID: " + raw) }
}
