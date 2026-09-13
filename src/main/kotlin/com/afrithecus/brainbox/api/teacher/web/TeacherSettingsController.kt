package com.afrithecus.brainbox.api.teacher.web

import com.afrithecus.brainbox.api.common.error.notFound
import com.afrithecus.brainbox.api.identity.model.CurrentUser
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import com.afrithecus.brainbox.api.teacher.TeacherSettingsService
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/** Teacher settings and profile (doc 04 teacher settings). */
@RestController
@RequestMapping("/teacher")
class TeacherSettingsController(
    private val service: TeacherSettingsService,
    private val userRepository: UserRepository,
) {
    private fun teacher(current: CurrentUser) =
        userRepository.findById(current.userId).orElseThrow { notFound("User not found") }

    @GetMapping("/settings")
    fun settings(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @RequestParam(required = false) teacherId: String?,
    ): TeacherSettingsPayload = service.settings(teacher(currentUser))

    @PutMapping("/settings")
    fun updateSettings(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @RequestBody settings: TeacherSettingsPayload,
    ): TeacherSettingsPayload = service.updateSettings(teacher(currentUser), settings)

    @GetMapping("/profile")
    fun profile(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @RequestParam(required = false) teacherId: String?,
    ): TeacherProfilePayload = service.profile(teacher(currentUser))

    @PutMapping("/profile")
    fun updateProfile(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @RequestBody profile: TeacherProfilePayload,
    ): TeacherProfilePayload = service.updateProfile(teacher(currentUser), profile)
}
