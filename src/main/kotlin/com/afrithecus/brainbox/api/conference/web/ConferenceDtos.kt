package com.afrithecus.brainbox.api.conference.web

/**
 * Conference payloads. A single superset covers the teacher (TeacherConferenceSlot /
 * TeacherConferenceBooking) and parent (ConferenceSlot / ConferenceBooking) models;
 * Gson ignores the fields each side does not use.
 */

data class ConferenceSlotPayload(
    val id: String,
    val teacherId: String = "",
    val teacherName: String = "",
    val title: String = "",
    val date: Long = 0,
    val startTime: String = "",
    val endTime: String = "",
    val time: String = "",
    val durationMinutes: Int = 30,
    val duration: Int = 30,
    val maxBookings: Int = 1,
    val meetLink: String? = null,
    val isVirtual: Boolean = true,
    val location: String? = null,
    val isRecurring: Boolean = false,
    val recurrenceRule: String? = null,
    val status: String = "OPEN",
    val cancellationReason: String? = null,
    val audienceTarget: String = "WHOLE_SCHOOL",
    val createdByRole: String = "TEACHER",
    val linkedLiveClassId: String? = null,
    val createdAt: Long = 0,
    val isBooked: Boolean = false,
)

data class ConferenceBookingPayload(
    val id: String,
    val slotId: String = "",
    val parentId: String = "",
    val parentName: String = "",
    val childId: String = "",
    val childName: String = "",
    val childGrade: String = "",
    val teacherName: String = "",
    val date: Long = 0,
    val time: String = "",
    val meetLink: String? = null,
    val notes: String? = null,
    val status: String = "CONFIRMED",
)
