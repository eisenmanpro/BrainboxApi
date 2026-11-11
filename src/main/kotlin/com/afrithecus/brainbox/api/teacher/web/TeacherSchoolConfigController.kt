package com.afrithecus.brainbox.api.teacher.web

import com.afrithecus.brainbox.api.common.error.notFound
import com.afrithecus.brainbox.api.identity.model.CurrentUser
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import com.afrithecus.brainbox.api.teacher.TeacherChangeRequestService
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * The drop-down source for teacher settings (N3): the school's class catalogue
 * (ICT-admin populated) and the BrainBox-managed subject catalogue. Read-only
 * and self-scoped to the signed-in teacher's school.
 */
@RestController
@RequestMapping("/teacher")
@PreAuthorize("hasRole('TEACHER')")
class TeacherSchoolConfigController(
    private val service: TeacherChangeRequestService,
    private val userRepository: UserRepository,
) {
    private fun teacher(current: CurrentUser) =
        userRepository.findById(current.userId).orElseThrow { notFound("User not found") }

    @GetMapping("/school-config")
    fun schoolConfig(@AuthenticationPrincipal currentUser: CurrentUser): TeacherSchoolConfigPayload =
        service.schoolConfigFor(teacher(currentUser))
}
