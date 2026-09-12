package com.afrithecus.brainbox.api.announcement.web

import com.afrithecus.brainbox.api.announcement.AnnouncementService
import com.afrithecus.brainbox.api.common.error.notFound
import com.afrithecus.brainbox.api.identity.model.CurrentUser
import com.afrithecus.brainbox.api.identity.repository.UserRepository
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

/** Teacher announcements (docs/ongoing/api_announcement_changes.md). */
@RestController
@RequestMapping("/teacher/announcements")
class AnnouncementController(
    private val service: AnnouncementService,
    private val userRepository: UserRepository,
) {
    private fun teacher(current: CurrentUser) =
        userRepository.findById(current.userId).orElseThrow { notFound("User not found") }

    @GetMapping
    fun own(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @RequestParam(required = false) teacherId: String?,
    ): List<TeacherAnnouncementPayload> = service.own(teacher(currentUser))

    @GetMapping("/received")
    fun received(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @RequestParam(required = false) teacherId: String?,
    ): List<TeacherAnnouncementPayload> = service.received(teacher(currentUser))

    @PostMapping
    fun create(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @RequestBody request: TeacherAnnouncementPayload,
    ): TeacherAnnouncementPayload = service.create(teacher(currentUser), request)

    @PutMapping("/{announcementId}")
    fun update(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable announcementId: String,
        @RequestBody request: TeacherAnnouncementPayload,
    ): TeacherAnnouncementPayload = service.update(teacher(currentUser), announcementId, request)

    @DeleteMapping("/{announcementId}")
    fun delete(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable announcementId: String,
    ): ResponseEntity<Void> {
        service.delete(teacher(currentUser), announcementId)
        return ResponseEntity.noContent().build()
    }

    @GetMapping("/{announcementId}/analytics")
    fun analytics(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable announcementId: String,
        @RequestParam(required = false) teacherId: String?,
    ): AnnouncementAnalyticsPayload = service.analytics(teacher(currentUser), announcementId)
}
