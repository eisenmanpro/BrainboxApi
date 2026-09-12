package com.afrithecus.brainbox.api.learning.web

import com.afrithecus.brainbox.api.common.error.notFound
import com.afrithecus.brainbox.api.identity.model.CurrentUser
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import com.afrithecus.brainbox.api.learning.TeacherContentService
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * Teacher content management (docs/ongoing/api_content_changes.md). The signed-in
 * teacher is authoritative; the client's teacherId query is ignored.
 */
@RestController
@RequestMapping("/teacher")
class TeacherContentController(
    private val service: TeacherContentService,
    private val userRepository: UserRepository,
) {
    private fun teacher(current: CurrentUser) =
        userRepository.findById(current.userId).orElseThrow { notFound("User not found") }

    @GetMapping("/posts")
    fun posts(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @RequestParam(required = false) teacherId: String?,
    ): List<TeacherPostPayload> = service.posts(teacher(currentUser))

    @GetMapping("/content")
    fun content(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @RequestParam(required = false) teacherId: String?,
        @RequestParam(required = false) type: String?,
    ): List<TeacherContentPayload> = service.content(teacher(currentUser), type)

    @PostMapping("/content/post")
    fun createPost(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @RequestBody post: TeacherPostPayload,
    ): TeacherPostPayload = service.createPost(teacher(currentUser), post)

    @PutMapping("/content/material/{materialId}")
    fun updateMaterial(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable materialId: String,
        @RequestBody request: MaterialUpdateRequest,
    ): TeacherContentPayload = service.updateMaterial(teacher(currentUser), materialId, request)

    @PostMapping("/content/material/{materialId}/publish")
    fun publishMaterial(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable materialId: String,
        @RequestParam(required = false) publishDate: Long?,
    ): TeacherContentPayload = service.publishMaterial(teacher(currentUser), materialId)

    @DeleteMapping("/content/material/{materialId}")
    fun deleteMaterial(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable materialId: String,
    ): ResponseEntity<Void> {
        service.deleteMaterial(teacher(currentUser), materialId)
        return ResponseEntity.noContent().build()
    }

    @GetMapping("/content/drafts")
    fun drafts(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @RequestParam(required = false) teacherId: String?,
    ): List<TeacherContentDraftPayload> = service.drafts(teacher(currentUser))

    @PostMapping("/content/drafts")
    fun saveDraft(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @RequestBody draft: TeacherContentDraftPayload,
    ): TeacherContentDraftPayload = service.saveDraft(teacher(currentUser), draft)

    @DeleteMapping("/content/drafts/{draftId}")
    fun deleteDraft(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable draftId: String,
    ): ResponseEntity<Void> {
        service.deleteDraft(teacher(currentUser), draftId)
        return ResponseEntity.noContent().build()
    }

    @GetMapping("/content/{contentId}/analytics")
    fun analytics(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable contentId: String,
        @RequestParam(required = false) teacherId: String?,
    ): ContentAnalyticsPayload = service.analytics(teacher(currentUser), contentId)
}
