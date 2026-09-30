package com.afrithecus.brainbox.api.classes.web

import com.afrithecus.brainbox.api.classes.ClassTransitionService
import com.afrithecus.brainbox.api.common.error.notFound
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.model.CurrentUser
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * Learner search + grade/stream transitions (docs/ongoing/product_ops_roadmap.md item 3).
 * The actor is always the bearer-token principal.
 */
@RestController
@RequestMapping("/teacher/students")
@PreAuthorize("hasAnyRole('TEACHER','CTEACHER','GRADE_COORDINATOR','ICT_ADMIN')")
class ClassTransitionController(
    private val service: ClassTransitionService,
    private val users: UserRepository,
) {

    private fun actor(current: CurrentUser): UserEntity =
        users.findById(current.userId).orElse(null) ?: throw notFound("User not found")

    @GetMapping("/search")
    fun search(
        @AuthenticationPrincipal current: CurrentUser,
        @RequestParam(required = false) q: String?,
        @RequestParam(required = false) grade: String?,
        @RequestParam(required = false) stream: String?,
        @RequestParam(required = false) classId: String?,
        @RequestParam(required = false) limit: Int?,
    ): List<TransitionCandidatePayload> =
        service.search(actor(current), q, grade, stream, classId, limit ?: 20)

    /** The coordinator's on/off switch for the whole school (item 4). */
    @GetMapping("/transitions/config")
    fun config(@AuthenticationPrincipal current: CurrentUser): TransitionSettingsPayload =
        TransitionSettingsPayload(service.transitionsEnabled(actor(current)))

    @PutMapping("/transitions/config")
    fun setConfig(
        @AuthenticationPrincipal current: CurrentUser,
        @RequestBody request: TransitionSettingsPayload,
    ): TransitionSettingsPayload =
        TransitionSettingsPayload(service.setTransitionsEnabled(actor(current), request.enabled))

    @PostMapping("/{studentId}/transition")
    fun transition(
        @AuthenticationPrincipal current: CurrentUser,
        @PathVariable studentId: String,
        @RequestBody request: TransitionRequest,
    ): TransitionResultPayload = service.transition(actor(current), studentId, request)

    @GetMapping("/{studentId}/transitions")
    fun history(
        @AuthenticationPrincipal current: CurrentUser,
        @PathVariable studentId: String,
    ): List<TransitionHistoryPayload> = service.history(actor(current), studentId)
}
