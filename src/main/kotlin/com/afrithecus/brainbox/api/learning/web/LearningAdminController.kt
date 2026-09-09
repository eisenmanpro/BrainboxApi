package com.afrithecus.brainbox.api.learning.web

import com.afrithecus.brainbox.api.identity.model.CurrentUser
import com.afrithecus.brainbox.api.learning.LearningService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** Learning content authoring (ADMIN gate for now). */
@RestController
@RequestMapping("/admin/learning")
@PreAuthorize("hasRole('ADMIN')")
class LearningAdminController(private val service: LearningService) {

    @PostMapping("/posts")
    fun create(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @Valid @RequestBody request: CreatePostRequest,
    ): LearningPostPayload = service.createPost(currentUser.userId, request)

    @GetMapping("/posts/{id}")
    fun get(@PathVariable id: String): LearningPostPayload = service.adminGet(id)

    @DeleteMapping("/posts/{id}")
    fun unpublish(@PathVariable id: String): ResponseEntity<Void> {
        service.unpublish(id)
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build()
    }
}
