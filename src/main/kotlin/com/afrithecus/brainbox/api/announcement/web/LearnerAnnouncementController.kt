package com.afrithecus.brainbox.api.announcement.web

import com.afrithecus.brainbox.api.announcement.AnnouncementService
import com.afrithecus.brainbox.api.common.error.notFound
import com.afrithecus.brainbox.api.identity.model.CurrentUser
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * Announcements shown to the signed-in learner (docs/ongoing/api_announcement_changes.md):
 * the read-only feed of delivered, unexpired announcements targeting the account.
 */
@RestController
@RequestMapping("/announcements")
class LearnerAnnouncementController(
    private val service: AnnouncementService,
    private val userRepository: UserRepository,
) {

    @GetMapping("/for-me")
    fun forMe(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @RequestParam(required = false) grade: String?,
    ): List<TeacherAnnouncementPayload> {
        val user = userRepository.findById(currentUser.userId).orElseThrow { notFound("User not found") }
        return service.forMe(user, grade)
    }
}
