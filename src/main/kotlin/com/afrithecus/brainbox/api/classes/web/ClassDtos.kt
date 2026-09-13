package com.afrithecus.brainbox.api.classes.web

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty

// ---------------------------------------------------------------------------
// Classes & roster payloads (doc 04 §2.2 + homework prerequisites).
// ---------------------------------------------------------------------------

data class CreateClassRequest(
    @field:NotBlank
    val name: String,
    @field:NotBlank
    val grade: String,
    @field:NotBlank
    val subject: String,
)

data class TeacherClassPayload(
    val classId: String,
    val name: String,
    val grade: String,
    val subject: String,
    val studentCount: Int,
)

data class AddStudentsRequest(
    @field:NotEmpty
    val studentIds: List<String>,
)

/**
 * Roster row (docs/ongoing/api_teacher_roster_changes.md). Field names match the
 * client StudentInClass model; this single read backs the roster, gradebook,
 * attendance, feedback, CBC analytics and homework surfaces.
 */
data class StudentInClassPayload(
    val id: String,
    val name: String,
    val admissionNumber: String? = null,
    val grade: Int = 0,
    val avatarUrl: String? = null,
    val averageScore: Int = 0,
    val currentStreak: Int = 0,
    val lastActive: Long = 0L,
    val parentId: String? = null,
    val cbcCompetencySummary: Map<String, String> = emptyMap(),
)
