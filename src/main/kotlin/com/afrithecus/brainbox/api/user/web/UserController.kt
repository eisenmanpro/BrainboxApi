package com.afrithecus.brainbox.api.user.web

import com.afrithecus.brainbox.api.identity.model.CurrentUser
import com.afrithecus.brainbox.api.user.UserProfileService
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** User profile/progress endpoints backing the student dashboard (doc 14 §3). */
@RestController
@RequestMapping("/users")
class UserController(
    private val service: UserProfileService,
) {

    @GetMapping("/{userId}/profile")
    fun profile(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable userId: String,
    ): UserProfilePayload = service.profile(currentUser, userId)

    @GetMapping("/{userId}/progress")
    fun progress(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable userId: String,
    ): UserProgressPayload = service.progress(currentUser, userId)
}
