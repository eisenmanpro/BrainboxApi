package com.afrithecus.brainbox.api.conference.web

import com.afrithecus.brainbox.api.common.error.notFound
import com.afrithecus.brainbox.api.conference.ConferenceService
import com.afrithecus.brainbox.api.identity.model.CurrentUser
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.JsonNode

/** Teacher conference slots and bookings (doc 04 section 15). */
@RestController
@RequestMapping("/teacher/conference")
class TeacherConferenceController(
    private val service: ConferenceService,
    private val userRepository: UserRepository,
) {
    private fun teacher(current: CurrentUser) =
        userRepository.findById(current.userId).orElseThrow { notFound("User not found") }

    @GetMapping("/{teacherId}/slots")
    fun slots(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable teacherId: String,
    ): List<ConferenceSlotPayload> = service.teacherSlots(teacher(currentUser))

    @PostMapping("/slot")
    fun createSlot(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @RequestBody slot: ConferenceSlotPayload,
    ): ConferenceSlotPayload = service.createSlot(teacher(currentUser), slot)

    @PutMapping("/slot")
    fun updateSlot(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @RequestBody slot: ConferenceSlotPayload,
    ): ConferenceSlotPayload = service.updateSlot(teacher(currentUser), slot)

    @DeleteMapping("/slot/{slotId}")
    fun deleteSlot(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable slotId: String,
    ): ResponseEntity<Void> {
        service.deleteSlot(teacher(currentUser), slotId)
        return ResponseEntity.noContent().build()
    }

    @GetMapping("/slot/{slotId}/bookings")
    fun bookings(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable slotId: String,
    ): List<ConferenceBookingPayload> = service.bookingsForSlot(teacher(currentUser), slotId)

    @PostMapping("/booking/{bookingId}/reminder")
    fun reminder(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable bookingId: String,
        @RequestBody(required = false) body: JsonNode?,
    ): ResponseEntity<Void> {
        service.sendReminder(teacher(currentUser), bookingId, body?.asString() ?: "EMAIL")
        return ResponseEntity.noContent().build()
    }

    @PatchMapping("/booking/{bookingId}/status")
    fun status(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable bookingId: String,
        @RequestBody body: Map<String, String>,
    ): ConferenceBookingPayload =
        service.updateBookingStatus(teacher(currentUser), bookingId, body["status"].orEmpty())

    @GetMapping("/slot/{slotId}/meet-link")
    fun meetLink(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable slotId: String,
    ): String = service.meetLink(teacher(currentUser), slotId)
}
