package com.afrithecus.brainbox.api.learning.web

import com.afrithecus.brainbox.api.common.error.notFound
import com.afrithecus.brainbox.api.identity.model.CurrentUser
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import com.afrithecus.brainbox.api.learning.MaterialsService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/** Reading materials + progress (doc 03 §3). */
@RestController
@RequestMapping("/materials")
class MaterialsController(
    private val service: MaterialsService,
    private val userRepository: UserRepository,
) {
    private fun user(current: CurrentUser) =
        userRepository.findById(current.userId).orElseThrow { notFound("User not found") }

    @GetMapping("/readable")
    fun readable(@AuthenticationPrincipal currentUser: CurrentUser): List<ReadableFilePayload> =
        service.list(user(currentUser))

    @GetMapping("/readable/{id}")
    fun detail(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable id: String,
    ): ReadableFilePayload = service.detail(user(currentUser), id)

    @GetMapping("/readable/category/{category}")
    fun byCategory(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable category: String,
    ): List<ReadableFilePayload> = service.list(user(currentUser), category)

    @GetMapping("/readable/search")
    fun search(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @RequestParam q: String,
    ): List<ReadableFilePayload> = service.search(user(currentUser), q)

    @PostMapping("/reading/progress")
    fun readingProgress(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @Valid @RequestBody request: ReadingProgressRequest,
    ): ReadingProgressPayload = service.upsertProgress(user(currentUser), request)

    @GetMapping("/reading/progress/{fileId}")
    fun readingProgressGet(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable fileId: String,
    ): ReadingProgressPayload? = service.progress(user(currentUser), fileId)

    @PostMapping("/reading/session")
    fun readingSession(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @Valid @RequestBody request: ReadingSessionRequest,
    ): ReadingSessionPayload = service.recordSession(user(currentUser), request)

    @GetMapping("/reading/sessions/{fileId}")
    fun readingSessions(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable fileId: String,
    ): List<ReadingSessionPayload> = service.sessions(user(currentUser), fileId)

    // convenience: also create materials via POST under admin? no - admin controller
}
