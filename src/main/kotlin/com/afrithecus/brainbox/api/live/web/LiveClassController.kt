package com.afrithecus.brainbox.api.live.web

import com.afrithecus.brainbox.api.common.error.invalidArgument
import com.afrithecus.brainbox.api.identity.model.CurrentUser
import com.afrithecus.brainbox.api.live.LiveClassService
import jakarta.validation.Valid
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/** Live classes (doc 05 §4): list/detail/register/attendance/polls. */
@RestController
@RequestMapping("/live")
class LiveClassController(private val service: LiveClassService) {

    @GetMapping("/now")
    fun liveNow(): List<LiveClassPayload> = service.ongoing()

    @GetMapping("/upcoming")
    fun upcoming(): List<LiveClassPayload> = service.upcoming()

    @GetMapping("/ongoing")
    fun ongoing(): List<LiveClassPayload> = service.ongoing()

    @GetMapping("/completed")
    fun completed(): List<LiveClassPayload> = service.completed()

    @GetMapping("/replays")
    fun replays(): List<RecordedReplayPayload> = service.replays()

    @GetMapping("/spotlight")
    fun spotlight(): TeacherSpotlightPayload = service.spotlight()

    @GetMapping("/class/{classId}")
    fun detail(@PathVariable classId: String): LiveClassPayload = service.detail(classId)

    @PostMapping("/class/{classId}/register")
    fun register(
        @AuthenticationPrincipal current: CurrentUser,
        @PathVariable classId: String,
    ): Map<String, String> = service.register(current, classId)

    @PostMapping("/class/{classId}/attendance")
    fun attendance(
        @AuthenticationPrincipal current: CurrentUser,
        @PathVariable classId: String,
        @RequestBody request: AttendanceRequest,
    ): Map<String, String> = service.recordAttendance(current, classId, request)

    @GetMapping("/class/{classId}/polls")
    fun polls(@PathVariable classId: String): List<LivePollPayload> = service.polls(classId)

    @PostMapping("/class/{classId}/poll")
    fun createPoll(
        @AuthenticationPrincipal current: CurrentUser,
        @PathVariable classId: String,
        @Valid @RequestBody request: CreatePollRequest,
    ): LivePollPayload = service.createPoll(current, classId, request)

    @PostMapping("/class/{classId}/poll/{pollId}/vote")
    fun vote(
        @AuthenticationPrincipal current: CurrentUser,
        @PathVariable classId: String,
        @PathVariable pollId: String,
        @RequestParam(required = false) optionIndex: Int?,
        @RequestBody(required = false) body: Map<String, Int>?,
    ): LivePollPayload {
        val index = optionIndex ?: body?.get("optionIndex")
            ?: throw invalidArgument("optionIndex is required")
        return service.vote(current, classId, pollId, index)
    }
}
