package com.afrithecus.brainbox.api.timetable.web

import com.afrithecus.brainbox.api.common.error.notFound
import com.afrithecus.brainbox.api.identity.model.CurrentUser
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import com.afrithecus.brainbox.api.timetable.TimetableService
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/** Teacher room bookings (doc 04 section 9.2). */
@RestController
@RequestMapping("/teacher/rooms")
class RoomBookingController(
    private val service: TimetableService,
    private val userRepository: UserRepository,
) {
    private fun teacher(current: CurrentUser) =
        userRepository.findById(current.userId).orElseThrow { notFound("User not found") }

    @GetMapping("/bookings")
    fun bookings(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @RequestParam(required = false) roomId: String?,
    ): List<RoomBookingPayload> = service.bookings(teacher(currentUser), roomId)

    @PostMapping("/book")
    fun book(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @RequestBody booking: RoomBookingPayload,
    ): RoomBookingPayload = service.bookRoom(teacher(currentUser), booking)

    @DeleteMapping("/bookings/{bookingId}")
    fun cancel(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable bookingId: String,
    ): ResponseEntity<Void> {
        service.cancelBooking(teacher(currentUser), bookingId)
        return ResponseEntity.noContent().build()
    }
}
