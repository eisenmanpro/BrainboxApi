package com.afrithecus.brainbox.api.attendance.model

/** Attendance status; matches the Android AttendanceStatus enum exactly. */
enum class AttendanceStatus { PRESENT, ABSENT, LATE, EXCUSED }

/** Risk tier shared by the attendance-performance intelligence (doc 04 §4.4). */
enum class AttendanceRiskTier { LOW, MEDIUM, HIGH, CRITICAL }

/** Attendance-vs-performance quadrant (doc 04 §4.4). */
enum class AttendancePerformanceQuadrant {
    HIGH_ATTEND_HIGH_PERF,
    HIGH_ATTEND_LOW_PERF,
    LOW_ATTEND_HIGH_PERF,
    LOW_ATTEND_LOW_PERF,
}

/** Trend direction for a learner's attendance (doc 04 §4.4). */
enum class AttendanceTrendDirection { UP, DOWN, STABLE }

/** Chronic absenteeism alert severity. */
enum class AttendanceAlertSeverity { LOW, MEDIUM, HIGH }
