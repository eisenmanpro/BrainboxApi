package com.afrithecus.brainbox.api.profile.web

import com.afrithecus.brainbox.api.common.error.ApiErrorCode
import com.afrithecus.brainbox.api.common.error.ApiException
import com.afrithecus.brainbox.api.common.error.notFound
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.model.CurrentUser
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import com.afrithecus.brainbox.api.profile.ProfileService
import jakarta.validation.Valid
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** Profile settings (BACKEND_BLUEPRINT §13). Path userId must be the caller (doc 01 §1). */
@RestController
@RequestMapping("/profile")
class ProfileController(
    private val service: ProfileService,
    private val userRepository: UserRepository,
) {

    @GetMapping("/settings/{userId}")
    fun settings(
        @AuthenticationPrincipal current: CurrentUser,
        @PathVariable userId: String,
    ): UserSettingsPayload = service.settings(requireSelf(current, userId))

    @PatchMapping("/settings/{userId}")
    fun update(
        @AuthenticationPrincipal current: CurrentUser,
        @PathVariable userId: String,
        @Valid @RequestBody request: UpdateSettingsRequest,
    ): UserSettingsPayload = service.update(requireSelf(current, userId), request)

    private fun requireSelf(current: CurrentUser, userId: String): UserEntity {
        if (userId != current.userId.toString()) {
            throw ApiException(ApiErrorCode.FORBIDDEN, "Cannot access another user's profile")
        }
        return userRepository.findById(current.userId).orElseThrow { notFound("User not found") }
    }
}
