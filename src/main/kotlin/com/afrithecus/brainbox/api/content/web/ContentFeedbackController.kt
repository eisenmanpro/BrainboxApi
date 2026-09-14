package com.afrithecus.brainbox.api.content.web

import com.afrithecus.brainbox.api.common.error.notFound
import com.afrithecus.brainbox.api.content.ReviewSurfaceService
import com.afrithecus.brainbox.api.identity.model.CurrentUser
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Phase 7.4b teacher content feedback. One rating per (teacher, content item):
 * re-submitting updates the existing row, so the preference dataset keeps a single
 * current signal per reviewer.
 */
@RestController
@RequestMapping("/teacher/content")
@PreAuthorize("hasAnyRole('TEACHER','CTEACHER','GRADE_COORDINATOR','ICT_ADMIN')")
class ContentFeedbackController(
    private val service: ReviewSurfaceService,
    private val users: UserRepository,
) {

    @PostMapping("/feedback")
    fun submit(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @RequestBody request: SubmitFeedbackRequest,
    ): ContentFeedbackPayload = service.submitFeedback(actor(currentUser), request)

    @GetMapping("/feedback")
    fun get(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @RequestParam contentType: String,
        @RequestParam contentId: String,
    ): ContentFeedbackPayload? =
        service.listFeedback(actor(currentUser), contentType, UUID.fromString(contentId))

    private fun actor(currentUser: CurrentUser) =
        users.findById(currentUser.userId).orElseThrow { notFound("User not found") }
}
