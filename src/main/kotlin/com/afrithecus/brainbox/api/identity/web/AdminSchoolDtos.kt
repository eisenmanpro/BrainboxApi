package com.afrithecus.brainbox.api.identity.web

/** Admin/coordinator payloads (docs/ongoing/api_admin_changes.md). */
data class ClassRankingPayload(
    val classId: String,
    val className: String,
    val average: Double,
    val rank: Int,
    val totalStudents: Int = 0,
    val trend: Double? = null,
)

data class SubjectSchoolPerformancePayload(
    val subject: String,
    val schoolMean: Double,
    val topScore: Int,
    val lowestScore: Int,
    val passRate: Double,
    val studentCount: Int,
)

data class TeacherPerformancePayload(
    val teacherId: String,
    val teacherName: String,
    val average: Double,
    val subjects: List<String> = emptyList(),
    val classesCount: Int = 0,
    val studentsCount: Int = 0,
)

data class SchoolAnalyticsPayload(
    val schoolId: String,
    val schoolName: String,
    val term: String,
    val year: Int,
    val overallPerformance: Double,
    val previousOverallPerformance: Double? = null,
    val classRankings: List<ClassRankingPayload> = emptyList(),
    val subjectPerformance: List<SubjectSchoolPerformancePayload> = emptyList(),
    val teacherPerformance: List<TeacherPerformancePayload> = emptyList(),
    val totalStudents: Int = 0,
    val totalTeachers: Int = 0,
    val totalClasses: Int = 0,
    val gradeDistribution: Map<String, Int> = emptyMap(),
    val generatedAt: Long,
)

data class GradeConfigSummaryPayload(
    val configId: String,
    val gradeLevel: String,
    val displayName: String,
    val classTeacherName: String? = null,
    val coordinatorName: String? = null,
    val active: Boolean = true,
)

data class UserApprovalRequestPayload(
    val userId: String,
    val name: String,
    val phoneNumber: String,
    val schoolName: String,
    val requestedRole: String,
    val status: String,
)

data class SystemSettingsPayload(
    val schoolId: String,
    val maintenanceMode: Boolean = false,
    val registrationOpen: Boolean = true,
)

data class AuditLogEntryPayload(
    val logId: String,
    val actorName: String,
    val action: String,
    val timestamp: Long,
)

data class ClassAttendanceSummaryPayload(
    val classId: String,
    val className: String,
    val grade: Int,
    val attendanceRate: Double,
    val totalStudents: Int,
    val chronicAbsentees: Int,
)

data class SchoolAttendanceOverviewPayload(
    val schoolId: String,
    val overallAttendanceRate: Double,
    val chronicAbsenteeCount: Int,
    val classes: List<ClassAttendanceSummaryPayload> = emptyList(),
)

data class BackupResultPayload(
    val success: Boolean,
    val message: String,
    val backupId: String,
    val createdAt: Long,
    val sizeBytes: Long,
    val fileName: String,
    val sha256: String,
)

data class BackupSummaryPayload(
    val backupId: String,
    val fileName: String,
    val sizeBytes: Long,
    val sha256: String? = null,
    val status: String = "READY",
    val createdAt: Long,
)

/** A backup artifact streamed back to the admin (service -> controller). */
data class BackupDownload(val fileName: String, val bytes: ByteArray)
