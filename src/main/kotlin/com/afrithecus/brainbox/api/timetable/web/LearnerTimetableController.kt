package com.afrithecus.brainbox.api.timetable.web

import com.afrithecus.brainbox.api.common.error.ApiErrorCode
import com.afrithecus.brainbox.api.common.error.ApiException
import com.afrithecus.brainbox.api.common.error.notFound
import com.afrithecus.brainbox.api.identity.model.CurrentUser
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import com.afrithecus.brainbox.api.timetable.LearnerTimetableService
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/** The signed-in learner's own timetable (docs/ongoing/api_timetable_changes.md). */
@RestController
@RequestMapping("/student")
class LearnerTimetableController(
    private val service: LearnerTimetableService,
    private val userRepository: UserRepository,
) {

    @GetMapping("/timetable")
    fun timetable(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @RequestParam(required = false) studentId: String?,
    ): List<LearnerTimetableSlotPayload> {
        val user = userRepository.findById(currentUser.userId).orElseThrow { notFound("User not found") }
        if (!studentId.isNullOrBlank() && studentId != user.id.toString()) {
            throw ApiException(ApiErrorCode.FORBIDDEN, "You can only view your own timetable")
        }
        return service.timetable(user)
    }
}
