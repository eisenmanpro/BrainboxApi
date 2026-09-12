package com.afrithecus.brainbox.api.study.web

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank

/** Study tool payloads (doc 03 §9). */

data class StudySessionPayload(
    val id: String,
    val userId: String,
    val subject: String,
    val topic: String,
    val startTime: Long,
    val endTime: Long,
    val durationMinutes: Int,
    val focusScore: Int,
)

data class RecordStudySessionRequest(
    @field:NotBlank val userId: String,
    @field:NotBlank val subject: String,
    val topic: String = "",
    val startTime: Long,
    val endTime: Long,
    @field:Min(0) @field:Max(100) val focusScore: Int = 0,
    val durationMinutes: Int? = null,
)

data class StudyInsightsPayload(
    val totalStudyHours: Double,
    val averageSessionDuration: Int,
    val mostStudiedSubject: String,
    val streakDays: Int,
    val weeklyGoal: Int,
    val weeklyProgress: Int,
    val recommendations: List<String>,
)
