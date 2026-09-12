package com.afrithecus.brainbox.api.live.web

import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.NotNull

/**
 * Live class payloads. LiveClassPayload is a superset of the Android LiveClass
 * and UpcomingLiveClass models so both client APIs can read /live/upcoming.
 */

data class MaterialPayload(val name: String, val url: String)

data class LiveClassPayload(
    val id: String,
    val title: String,
    val teacherId: String,
    val teacherName: String,
    val subject: String,
    val description: String = "",
    val scheduledStart: Long = 0L,
    val scheduledEnd: Long = 0L,
    val status: String = "SCHEDULED",
    val joinUrl: String? = null,
    val recordingUrl: String? = null,
    val participantCount: Int = 0,
    val maxParticipants: Int = 100,
    val thumbnailUrl: String? = null,
    val materials: List<MaterialPayload> = emptyList(),
    /** UpcomingLiveClass extras (present only on /live/upcoming). */
    val day: String? = null,
    val time: String? = null,
    val scheduledStartMillis: Long = 0L,
)

data class RecordedReplayPayload(
    val id: String,
    val title: String,
    val subject: String,
    val views: String,
    val thumbnail: String,
    val videoUrl: String? = null,
    val durationSeconds: Long = 0,
    val createdAt: Long = 0,
)

data class TeacherSpotlightPayload(
    val id: String,
    val name: String,
    val photo: String,
    val subjects: List<String>,
    val rating: Float,
    val studentCount: Int,
    val classCount: Int,
    val successStory: String,
)

data class LivePollPayload(
    val id: String,
    val classId: String,
    val question: String,
    val options: List<String>,
    val votes: Map<Int, Int> = emptyMap(),
    val results: Map<String, Int> = emptyMap(),
    val isActive: Boolean = true,
    val createdAt: Long = 0L,
)

/** Attendance body: union of the client AttendanceRecord and doc 05 §4.5. */
data class AttendanceRequest(
    val userId: String? = null,
    val classId: String? = null,
    val date: Long? = null,
    val status: String? = null,
    val reason: String? = null,
    val checkInTime: Long? = null,
    val leaveTime: Long? = null,
    val durationMinutes: Int? = null,
    val isPresent: Boolean? = null,
)

data class CreatePollRequest(
    @field:NotBlank val question: String,
    @field:NotEmpty val options: List<String>,
)

data class CreateLiveClassRequest(
    @field:NotBlank val title: String,
    @field:NotBlank val subject: String,
    @field:NotBlank val teacherId: String,
    @field:NotNull val scheduledStart: Long,
    @field:NotNull val scheduledEnd: Long,
    val description: String? = null,
    @field:Min(1) val maxParticipants: Int = 100,
    val joinUrl: String? = null,
    val thumbnailUrl: String? = null,
    val materials: List<MaterialPayload> = emptyList(),
)

data class UpdateLiveClassStatusRequest(
    @field:NotBlank val status: String,
    val joinUrl: String? = null,
    val recordingUrl: String? = null,
)

// ---------------------------------------------------------------------------
// Teacher hosting surface (docs/ongoing/api_live_class_changes.md)
// ---------------------------------------------------------------------------

data class LiveClassSettingsPayload(
    val visibility: String = "CLASS_ONLY",
    val autoRecord: Boolean = true,
    val muteOnJoin: Boolean = true,
    val waitingRoom: Boolean = false,
    val allowChat: Boolean = true,
    val allowQandA: Boolean = true,
)

/** Body of POST/PUT teacher/live-classes[/{classId}] (the client TeacherLiveClass). */
data class TeacherLiveClassRequest(
    val id: String = "",
    val title: String = "",
    val subject: String = "",
    val description: String = "",
    val scheduledStart: Long = 0,
    val scheduledEnd: Long = 0,
    val status: String = "SCHEDULED",
    val settings: LiveClassSettingsPayload = LiveClassSettingsPayload(),
    val participantIds: List<String> = emptyList(),
    val materialIds: List<String> = emptyList(),
    val recordingUrl: String? = null,
    val analyticsId: String? = null,
)

data class LiveClassParticipantPayload(
    val classId: String,
    val userId: String,
    val userName: String,
    val role: String = "STUDENT",
    val isMuted: Boolean = false,
    val joinTime: Long = 0,
)

data class AttendanceDetailPayload(
    val userId: String,
    val userName: String,
    val role: String,
    val joinTime: Long,
    val leaveTime: Long? = null,
    val durationMinutes: Int = 0,
    val leftEarly: Boolean = false,
)

data class LiveClassAnalyticsPayload(
    val classId: String,
    val totalParticipants: Int,
    val peakParticipants: Int,
    val avgWatchTimeMinutes: Double,
    val totalChatMessages: Int,
    val totalPollResponses: Int,
    val totalQuestionsAsked: Int,
    val attendanceList: List<AttendanceDetailPayload> = emptyList(),
    val engagementTimeline: Map<Long, Int> = emptyMap(),
    val dropOffPoints: Map<Int, Int> = emptyMap(),
    val averageRating: Float = 0f,
    val feedbackComments: List<String> = emptyList(),
)

data class ChatMessagePayload(
    val id: String = "",
    val classId: String = "",
    val userId: String = "",
    val userName: String = "",
    val userRole: String = "STUDENT",
    val message: String = "",
    val timestamp: Long = 0,
    val isPinned: Boolean = false,
)
