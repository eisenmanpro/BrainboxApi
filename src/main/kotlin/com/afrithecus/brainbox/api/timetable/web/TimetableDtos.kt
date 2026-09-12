package com.afrithecus.brainbox.api.timetable.web

/**
 * Timetable and scheduling payloads. Field names and shapes mirror
 * com.afrithecus.brainbox.teacher.models and teacher/data/remote/TeacherTimetableApi
 * exactly, because the app caches these objects in Room and replays offline
 * writes verbatim.
 */

data class TimetableEntryPayload(
    val id: String = "",
    val teacherId: String = "",
    val classId: String = "",
    val className: String = "",
    val subject: String = "",
    val dayOfWeek: Int = 1,
    val startTime: String = "08:00",
    val endTime: String = "09:00",
    val roomId: String? = null,
    val roomName: String? = null,
    val colorHex: String? = null,
    val houseId: String? = null,
    val communityServiceId: String? = null,
    val peerCircleId: String? = null,
    val entryType: String = "LECTURE",
    val practicalBlockType: String = "NONE",
)

data class RoomBookingPayload(
    val id: String = "",
    val roomId: String = "",
    val roomName: String = "",
    val teacherId: String = "",
    val teacherName: String = "",
    val startTime: Long = 0,
    val endTime: Long = 0,
    val purpose: String = "",
)

data class HouseGroupPayload(
    val id: String = "",
    val houseId: String = "",
    val houseName: String = "",
    val houseColor: String = "",
    val teacherId: String = "",
    val classId: String = "",
    val memberCount: Int = 0,
    val studentIds: List<String> = emptyList(),
    val isActive: Boolean = true,
)

data class PeerCirclePayload(
    val id: String = "",
    val circleName: String = "",
    val teacherId: String = "",
    val studentIds: List<String> = emptyList(),
    val isActive: Boolean = true,
)

data class CommunityServicePayload(
    val id: String = "",
    val serviceName: String = "",
    val teacherId: String = "",
    val studentsAssigned: List<String> = emptyList(),
    val isActive: Boolean = true,
)

/** A learner's own scheduled class (GET student/timetable). */
data class LearnerTimetableSlotPayload(
    val id: String,
    val classId: String,
    val subject: String,
    val teacherName: String = "",
    val room: String? = null,
    val dayOfWeek: Int = 1,
    val startMillis: Long = 0,
    val endMillis: Long = 0,
)

data class ScheduleChangePayload(
    val id: String = "",
    val teacherId: String = "",
    val teacherName: String = "",
    val day: String = "",
    val className: String = "",
    val subject: String = "",
    val startTime: String = "",
    val endTime: String = "",
    val reason: String = "",
    val status: String = "PENDING",
    val requestedAt: Long = 0,
)
