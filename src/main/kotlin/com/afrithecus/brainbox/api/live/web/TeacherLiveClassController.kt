package com.afrithecus.brainbox.api.live.web

import com.afrithecus.brainbox.api.common.error.notFound
import com.afrithecus.brainbox.api.identity.model.CurrentUser
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import com.afrithecus.brainbox.api.live.TeacherLiveClassService
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

/** Teacher live-class hosting (docs/ongoing/api_live_class_changes.md). */
@RestController
@RequestMapping("/teacher/live-classes")
class TeacherLiveClassController(
    private val service: TeacherLiveClassService,
    private val userRepository: UserRepository,
) {
    private fun teacher(current: CurrentUser) =
        userRepository.findById(current.userId).orElseThrow { notFound("User not found") }

    @GetMapping
    fun list(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @RequestParam(required = false) teacherId: String?,
        @RequestParam(required = false) status: String?,
    ): List<LiveClassPayload> = service.teacherClasses(teacher(currentUser), status)

    @PostMapping
    fun schedule(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @RequestParam(required = false) teacherId: String?,
        @RequestBody request: TeacherLiveClassRequest,
    ): LiveClassPayload = service.schedule(teacher(currentUser), request)

    @PutMapping("/{classId}")
    fun update(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable classId: String,
        @RequestBody request: TeacherLiveClassRequest,
    ): LiveClassPayload = service.update(teacher(currentUser), classId, request)

    @DeleteMapping("/{classId}")
    fun cancel(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable classId: String,
    ): ResponseEntity<Void> {
        service.cancel(teacher(currentUser), classId)
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/{classId}/start")
    fun start(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable classId: String,
    ): LiveClassPayload = service.start(teacher(currentUser), classId)

    @PostMapping("/{classId}/end")
    fun end(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable classId: String,
    ): LiveClassPayload = service.end(teacher(currentUser), classId)

    @GetMapping("/{classId}/analytics")
    fun analytics(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable classId: String,
    ): LiveClassAnalyticsPayload = service.analytics(teacher(currentUser), classId)

    @GetMapping("/{classId}/participants")
    fun participants(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable classId: String,
    ): List<LiveClassParticipantPayload> = service.participants(teacher(currentUser), classId)

    @PostMapping("/{classId}/participants/{userId}/action")
    fun participantAction(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable classId: String,
        @PathVariable userId: String,
        @RequestParam action: String,
    ): ResponseEntity<Void> {
        service.participantAction(teacher(currentUser), classId, userId, action)
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/{classId}/polls")
    fun sendPoll(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable classId: String,
        @RequestBody poll: LivePollPayload,
    ): LivePollPayload = service.sendPoll(currentUser, classId, poll)

    @GetMapping("/{classId}/messages")
    fun messages(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable classId: String,
    ): List<ChatMessagePayload> = service.messages(teacher(currentUser), classId)
}
