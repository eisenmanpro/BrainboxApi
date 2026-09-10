package com.afrithecus.brainbox.api.profile.web

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Size

/**
 * Profile/settings payloads (BACKEND_BLUEPRINT §13). Field names and shapes match
 * the Android [com.afrithecus.brainbox.models.UserSettings] model exactly; values
 * the client may not set (plan, subscription, role extras) are server-derived and
 * therefore absent from [UpdateSettingsRequest].
 */
data class UserSettingsPayload(
    val fullName: String,
    val phoneNumber: String,
    val email: String?,
    val avatarUrl: String?,
    val school: String,
    val grade: String,
    val subjects: List<String>,
    val admissionNumber: String,
    val dailyReminderEnabled: Boolean,
    val dailyReminderTime: String?,
    val weeklyReportEnabled: Boolean,
    val preferredDifficulty: String,
    val interestSubjects: List<String>,
    val showOnLeaderboard: Boolean,
    val shareProgressWithSchool: Boolean,
    val allowTeacherView: Boolean,
    val pushNotificationsEnabled: Boolean,
    val pushContestReminders: Boolean,
    val pushAssignmentDue: Boolean,
    val pushQuizResults: Boolean,
    val pushWeeklyReport: Boolean,
    val pushBadges: Boolean,
    val smsReportsEnabled: Boolean,
    val emailNotificationsEnabled: Boolean,
    val plan: String,
    val subscriptionStatus: String,
    val subscriptionExpiry: Long?,
    val mpesaNumber: String?,
    val theme: String,
    val fontSize: String,
    val animationsEnabled: Boolean,
    val hapticFeedbackEnabled: Boolean,
    val personalizedContentEnabled: Boolean,
    val aiAdaptiveDifficultyEnabled: Boolean,
    val aiSensitivity: Int,
    val aiCoachingStyle: String,
    val cbcPathway: String,
    val competencyFocus: Map<String, Int>,
    val tscNumber: String?,
    val teacherCode: String?,
    val gradesTaught: List<String>,
    val roleLabel: String,
)

/** PATCH body: every field optional; absent/null means "leave unchanged". */
data class UpdateSettingsRequest(
    @field:Size(max = 255) val fullName: String? = null,
    @field:Size(max = 255) val email: String? = null,
    @field:Size(max = 255) val school: String? = null,
    @field:Size(max = 64) val grade: String? = null,
    val subjects: List<String>? = null,
    @field:Size(max = 64) val admissionNumber: String? = null,
    val dailyReminderEnabled: Boolean? = null,
    @field:Size(max = 16) val dailyReminderTime: String? = null,
    val weeklyReportEnabled: Boolean? = null,
    @field:Size(max = 32) val preferredDifficulty: String? = null,
    val interestSubjects: List<String>? = null,
    val showOnLeaderboard: Boolean? = null,
    val shareProgressWithSchool: Boolean? = null,
    val allowTeacherView: Boolean? = null,
    val pushNotificationsEnabled: Boolean? = null,
    val pushContestReminders: Boolean? = null,
    val pushAssignmentDue: Boolean? = null,
    val pushQuizResults: Boolean? = null,
    val pushWeeklyReport: Boolean? = null,
    val pushBadges: Boolean? = null,
    val smsReportsEnabled: Boolean? = null,
    val emailNotificationsEnabled: Boolean? = null,
    @field:Size(max = 32) val mpesaNumber: String? = null,
    @field:Size(max = 16) val fontSize: String? = null,
    val animationsEnabled: Boolean? = null,
    val hapticFeedbackEnabled: Boolean? = null,
    val personalizedContentEnabled: Boolean? = null,
    val aiAdaptiveDifficultyEnabled: Boolean? = null,
    @field:Min(1) @field:Max(5) val aiSensitivity: Int? = null,
    @field:Size(max = 32) val aiCoachingStyle: String? = null,
    @field:Size(max = 32) val cbcPathway: String? = null,
    val competencyFocus: Map<String, Int>? = null,
)
