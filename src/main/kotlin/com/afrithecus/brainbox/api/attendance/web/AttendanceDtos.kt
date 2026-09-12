package com.afrithecus.brainbox.api.attendance.web

// ---------------------------------------------------------------------------
// Attendance payloads matching the Android models exactly
// (teacher/models/TeacherModels.kt and models/ParentModels.kt).
// ---------------------------------------------------------------------------

data class AttendanceRecordPayload(
    val id: String,
    val classId: String,
    val studentId: String,
    val studentName: String,
    val date: Long,
    val status: String,
    val notes: String? = null,
    val recordedBy: String = "",
    val isAutoFromLiveClass: Boolean = false,
)

data class AttendanceTrendPointPayload(
    val date: Long,
    val attendancePercentage: Double,
)

data class ChronicAbsenteeismAlertPayload(
    val studentId: String,
    val studentName: String,
    val attendancePercentage: Double,
    val missedDaysCount: Int,
    val lastAbsentDate: Long,
    val severity: String,
)

data class AttendanceAnalyticsPayload(
    val classId: String,
    val averageAttendance: Double,
    val trendPoints: List<AttendanceTrendPointPayload>,
    val chronicAbsenteeismAlerts: List<ChronicAbsenteeismAlertPayload>,
    val weeklyHeatmap: Map<Int, Double>,
    val monthlyHeatmap: Map<Int, Double>,
)

data class AttendancePerformanceRiskSummaryPayload(
    val low: Int,
    val medium: Int,
    val high: Int,
    val critical: Int,
)

data class StudentAttendancePerformancePayload(
    val studentId: String,
    val studentName: String,
    val attendancePercentage: Double,
    val averageScore: Double,
    val masteryLevel: Double,
    val quadrant: String,
    val riskTier: String,
    val trend: String = "STABLE",
    val missedAssessmentsCount: Int = 0,
    val masteryDelta: Double = 0.0,
)

data class AttendancePerformanceTrendPointPayload(
    val date: Long,
    val attendancePercentage: Double,
    val averageScore: Double,
)

data class AttendancePerformanceAnalyticsPayload(
    val classId: String,
    val startDate: Long,
    val endDate: Long,
    val classAverageAttendance: Double,
    val classAverageScore: Double,
    val correlation: Double,
    val riskSummary: AttendancePerformanceRiskSummaryPayload,
    val students: List<StudentAttendancePerformancePayload>,
    val trendSeries: List<AttendancePerformanceTrendPointPayload>,
)

data class ChildAttendanceTrendPointPayload(
    val date: Long,
    val attendancePercentage: Double,
    val averageScore: Double,
)

data class ChildAttendancePerformancePayload(
    val attendancePercentage: Double,
    val averageScore: Double,
    val benchmarkAverage: Double,
    val riskTier: String,
    val impactInsight: String,
    val missedDaysCount: Int,
    val trendSeries: List<ChildAttendanceTrendPointPayload>,
)

data class PdfResultPayload(
    val fileUrl: String,
    val fileName: String,
    val fileSize: Long,
    val generatedAt: Long,
)
