package com.afrithecus.brainbox.api.media.web

import com.afrithecus.brainbox.api.common.error.notFound
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.model.CurrentUser
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import com.afrithecus.brainbox.api.media.MediaUploadResponsePayload
import com.afrithecus.brainbox.api.media.MediaUploadService
import jakarta.validation.Valid
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.support.ServletUriComponentsBuilder

/**
 * Presigned client-direct upload. Initiate returns a short-lived PUT URL for the
 * object store; confirm verifies the uploaded bytes and returns the served URL.
 * Any authenticated user may upload media/attachments; documents are teacher-only.
 */
@RestController
@RequestMapping("/media/uploads")
class MediaUploadController(
    private val service: MediaUploadService,
    private val userRepository: UserRepository,
) {

    @PostMapping
    fun initiate(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @Valid @RequestBody request: MediaUploadInitiateRequest,
    ): MediaUploadTicket = service.initiate(user(currentUser), request)

    @PostMapping("/{uploadId}/confirm")
    fun confirm(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable uploadId: String,
    ): MediaUploadResponsePayload = service.confirm(
        user(currentUser),
        uploadId,
        ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString(),
    )

    private fun user(current: CurrentUser): UserEntity =
        userRepository.findById(current.userId).orElseThrow { notFound("User not found") }
}
