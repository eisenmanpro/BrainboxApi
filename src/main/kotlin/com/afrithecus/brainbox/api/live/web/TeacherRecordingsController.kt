package com.afrithecus.brainbox.api.live.web

import com.afrithecus.brainbox.api.common.error.notFound
import com.afrithecus.brainbox.api.identity.model.CurrentUser
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import com.afrithecus.brainbox.api.live.TeacherLiveClassService
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/** A teacher's recorded class replays (docs/ongoing/api_live_class_changes.md). */
@RestController
@RequestMapping("/teacher/recordings")
class TeacherRecordingsController(
    private val service: TeacherLiveClassService,
    private val userRepository: UserRepository,
) {
    @GetMapping
    fun list(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @RequestParam(required = false) teacherId: String?,
    ): List<RecordedReplayPayload> {
        val teacher = userRepository.findById(currentUser.userId).orElseThrow { notFound("User not found") }
        return service.recordings(teacher)
    }
}
