package com.afrithecus.brainbox.api.conference.web

import com.afrithecus.brainbox.api.common.error.notFound
import com.afrithecus.brainbox.api.conference.ConferenceService
import com.afrithecus.brainbox.api.identity.model.CurrentUser
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** Parent conference browse/book/cancel (doc 04 section 15, api_conference_changes.md). */
@RestController
@RequestMapping("/parent/conference")
class ParentConferenceController(
    private val service: ConferenceService,
    private val userRepository: UserRepository,
) {
    private fun parent(current: CurrentUser) =
        userRepository.findById(current.userId).orElseThrow { notFound("User not found") }

    @GetMapping("/slots")
    fun slots(@AuthenticationPrincipal currentUser: CurrentUser): List<ConferenceSlotPayload> =
        service.parentSlots(parent(currentUser))

    @PostMapping("/book")
    fun book(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @RequestBody booking: ConferenceBookingPayload,
    ): ConferenceBookingPayload = service.book(parent(currentUser), booking)

    @DeleteMapping("/booking/{bookingId}")
    fun cancel(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable bookingId: String,
    ): ResponseEntity<Void> {
        service.cancelBooking(parent(currentUser), bookingId)
        return ResponseEntity.noContent().build()
    }

    @GetMapping("/bookings")
    fun bookings(@AuthenticationPrincipal currentUser: CurrentUser): List<ConferenceBookingPayload> =
        service.parentBookings(parent(currentUser))
}
