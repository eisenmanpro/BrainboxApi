package com.afrithecus.brainbox.api.announcement.web

import com.afrithecus.brainbox.api.announcement.SchoolAnnouncementService
import com.afrithecus.brainbox.api.common.error.notFound
import com.afrithecus.brainbox.api.identity.model.CurrentUser
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** Admin school announcements (docs/ongoing/open_gaps.md ANN-1). */
@RestController
@RequestMapping("/admin/schools")
@PreAuthorize("hasRole('ADMIN')")
class SchoolAnnouncementController(
    private val service: SchoolAnnouncementService,
    private val userRepository: UserRepository,
) {
    private fun admin(current: CurrentUser) =
        userRepository.findById(current.userId).orElseThrow { notFound("User not found") }

    @GetMapping("/{schoolId}/announcements")
    fun list(@PathVariable schoolId: String): List<SchoolAnnouncementPayload> = service.list(schoolId)

    @PostMapping("/{schoolId}/announcements")
    fun create(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable schoolId: String,
        @RequestBody request: SchoolAnnouncementPayload,
    ): SchoolAnnouncementPayload = service.create(admin(currentUser), schoolId, request)

    @PutMapping("/{schoolId}/announcements/{announcementId}")
    fun update(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable schoolId: String,
        @PathVariable announcementId: String,
        @RequestBody request: SchoolAnnouncementPayload,
    ): SchoolAnnouncementPayload = service.update(admin(currentUser), schoolId, announcementId, request)

    @DeleteMapping("/{schoolId}/announcements/{announcementId}")
    fun delete(
        @PathVariable schoolId: String,
        @PathVariable announcementId: String,
    ): ResponseEntity<Void> {
        service.delete(schoolId, announcementId)
        return ResponseEntity.noContent().build()
    }
}
