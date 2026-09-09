package com.afrithecus.brainbox.api.learning.web

import com.afrithecus.brainbox.api.identity.model.CurrentUser
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import com.afrithecus.brainbox.api.learning.LearningService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/** Student learning hub (doc 03 §2). */
@RestController
@RequestMapping("/learning")
class LearningController(
    private val service: LearningService,
    private val userRepository: UserRepository,
) {
    private fun user(current: CurrentUser) =
        userRepository.findById(current.userId).orElseThrow { com.afrithecus.brainbox.api.common.error.notFound("User not found") }

    @GetMapping("/featured")
    fun featured(@AuthenticationPrincipal currentUser: CurrentUser): List<LearningPostPayload> =
        service.featured(user(currentUser))

    @GetMapping("/trending")
    fun trending(@AuthenticationPrincipal currentUser: CurrentUser): List<LearningPostPayload> =
        service.trending(user(currentUser))

    @GetMapping("/subject/{subject}")
    fun bySubject(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable subject: String,
    ): List<LearningPostPayload> = service.bySubject(user(currentUser), subject)

    @GetMapping("/search")
    fun search(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @RequestParam q: String,
    ): List<LearningPostPayload> = service.search(user(currentUser), q)

    @GetMapping("/post/{postId}")
    fun detail(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable postId: String,
    ): LearningPostPayload = service.detail(user(currentUser), postId)

    @GetMapping("/post/{postId}/content")
    fun content(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable postId: String,
    ): List<LearningContentPayload> = service.contentOf(user(currentUser), postId)

    @PostMapping("/post/{postId}/view")
    fun recordView(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable postId: String,
    ): ResponseEntity<Void> {
        service.recordView(user(currentUser), postId)
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build()
    }
}
