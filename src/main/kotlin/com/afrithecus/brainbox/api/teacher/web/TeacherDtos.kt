package com.afrithecus.brainbox.api.teacher.web

/** Teacher settings + profile payloads matching teacher/models/TeacherModels.kt and models/UserModels.kt. */

data class TeacherNotificationPrefsPayload(
    val assignmentSubmissionAlerts: Boolean = true,
    val newExamPublish: Boolean = true,
    val parentMessages: Boolean = true,
    val staffBulletin: Boolean = true,
    val emailNotifications: Boolean = false,
)

data class TeacherSettingsPayload(
    val teacherId: String = "",
    val name: String = "",
    val phone: String = "",
    val email: String? = null,
    val schoolId: String = "",
    val schoolName: String = "",
    val subjectsTaught: List<String> = emptyList(),
    val tscNumber: String? = null,
    val defaultGradeLevel: Int? = null,
    val languagePreference: String = "en",
    val themePreference: String = "dark",
    val autoAttendance: Boolean = true,
    val notificationsEnabled: Boolean = true,
    val defaultGradeWeighting: Map<String, Double> = emptyMap(),
    val notificationPreferences: TeacherNotificationPrefsPayload = TeacherNotificationPrefsPayload(),
)

/** The user account shape returned by GET/PUT teacher/profile (models/UserModels.kt). */
data class TeacherProfilePayload(
    val id: String,
    val phoneNumber: String,
    val name: String,
    val role: String,
    val schoolId: String = "",
    val schoolName: String = "",
    val studentAdmissionNumber: String? = null,
    val grade: String? = null,
    val parentId: String? = null,
    val childId: String? = null,
    val createdAt: Long = 0,
    val lastLogin: Long = 0,
    val isActive: Boolean = true,
    val teacherCode: String? = null,
    val gradesTaught: List<String>? = null,
    val className: String? = null,
    val studentCount: Int? = null,
    val subjects: List<String>? = null,
    val tscNumber: String? = null,
    val gradeAssignments: List<String>? = null,
    val gradeLevelAssignments: List<String>? = null,
    val onboardingCompleted: Boolean = false,
    val managedSchoolId: String? = null,
    val verificationStatus: String = "VERIFIED",
    val ctcFrozen: Boolean = false,
    val passwordHash: String = "",
)
