package com.afrithecus.brainbox.api.messaging.web

import com.afrithecus.brainbox.api.common.error.notFound
import com.afrithecus.brainbox.api.identity.model.CurrentUser
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import com.afrithecus.brainbox.api.messaging.MessagingService
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/** School member directory (doc 05 §2.3). */
@RestController
@RequestMapping("/school/{schoolId}/members")
class SchoolMembersController(
    private val service: MessagingService,
    private val userRepository: UserRepository,
) {
    @GetMapping
    fun members(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable schoolId: String,
        @RequestParam(required = false) role: String?,
    ): List<SchoolMemberPayload> {
        val viewer = userRepository.findById(currentUser.userId).orElseThrow { notFound("User not found") }
        return service.schoolMembers(viewer, schoolId, role)
    }
}
