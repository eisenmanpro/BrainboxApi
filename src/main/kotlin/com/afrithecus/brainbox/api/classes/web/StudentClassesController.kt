package com.afrithecus.brainbox.api.classes.web

import com.afrithecus.brainbox.api.classes.ClassService
import com.afrithecus.brainbox.api.common.error.ApiErrorCode
import com.afrithecus.brainbox.api.common.error.ApiException
import com.afrithecus.brainbox.api.common.error.notFound
import com.afrithecus.brainbox.api.identity.model.CurrentUser
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** A student's own class list (roster memberships). */
@RestController
@RequestMapping("/classes")
class StudentClassesController(
    private val service: ClassService,
    private val userRepository: UserRepository,
) {
    @GetMapping("/my")
    fun my(@AuthenticationPrincipal currentUser: CurrentUser): List<TeacherClassPayload> {
        val user = userRepository.findById(currentUser.userId)
            .orElseThrow { notFound("User not found") }
        return service.myClasses(user)
    }
}
