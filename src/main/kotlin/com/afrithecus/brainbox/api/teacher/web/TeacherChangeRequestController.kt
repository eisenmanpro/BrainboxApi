package com.afrithecus.brainbox.api.teacher.web

import com.afrithecus.brainbox.api.common.error.notFound
import com.afrithecus.brainbox.api.identity.model.CurrentUser
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import com.afrithecus.brainbox.api.teacher.TeacherChangeRequestService
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** A teacher's own change requests (N3). Self-scoped: the token decides who asked. */
@RestController
@RequestMapping("/teacher/change-requests")
@PreAuthorize("hasRole('TEACHER')")
class TeacherChangeRequestController(
    private val service: TeacherChangeRequestService,
    private val userRepository: UserRepository,
) {
    private fun teacher(current: CurrentUser) =
        userRepository.findById(current.userId).orElseThrow { notFound("User not found") }

    @GetMapping
    fun mine(@AuthenticationPrincipal currentUser: CurrentUser): List<TeacherChangeRequestPayload> =
        service.mine(currentUser)

    @PostMapping
    fun create(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @RequestBody request: CreateTeacherChangeRequest,
    ): TeacherChangeRequestPayload = service.create(currentUser, request)
}
