package com.afrithecus.brainbox.api.studentanalytics.web

import com.afrithecus.brainbox.api.cbcratings.web.CbcStrandRatingPayload
import com.afrithecus.brainbox.api.contract.web.LearningContractPayload
import com.afrithecus.brainbox.api.feedback.web.TeacherFeedbackPayload

/** Student-analytics payloads matching teacher/models/TeacherModels.kt and models/ParentModels.kt. */

data class StudentQuickStatsPayload(
    val avgScore: Int = 0,
    val streakDays: Int = 0,
    val attendancePercentage: Double = 0.0,
    val totalXP: Int = 0,
    val nationalRank: Int = 0,
)

data class SubjectPerformancePayload(
    val subject: String,
    val score: Double,
    val color: Long = 0xFFFFFFFFL,
)

data class EngagementScorePayload(
    val weekly: Int = 0,
    val monthly: Int = 0,
    val factors: List<String> = emptyList(),
)

data class StudentAttendancePayload(
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

data class StudentHomeworkPayload(
    val id: String,
    val homeworkId: String,
    val studentId: String,
    val studentName: String,
    val submissionText: String? = null,
    val attachmentUrl: String? = null,
    val submittedAt: Long = 0,
    val grade: Int? = null,
    val feedback: String? = null,
    val isGraded: Boolean = false,
    val cbcStrandTag: String? = null,
    val status: String = "SUBMITTED",
)

data class ClassComparisonsPayload(
    val classAverageScore: Double = 0.0,
    val studentPercentile: Int = 0,
    val subjectComparisons: Map<String, Double> = emptyMap(),
)

data class StudentAnalyticsPayload(
    val studentId: String,
    val studentName: String,
    val quickStats: StudentQuickStatsPayload = StudentQuickStatsPayload(),
    val weeklyEngagement: List<Int> = emptyList(),
    val subjectPerformance: List<SubjectPerformancePayload> = emptyList(),
    val cbcCompetencies: List<CbcStrandRatingPayload> = emptyList(),
    val attendance: List<StudentAttendancePayload> = emptyList(),
    val engagement: EngagementScorePayload? = null,
    val homeworkHistory: List<StudentHomeworkPayload> = emptyList(),
    val feedbackHistory: List<TeacherFeedbackPayload> = emptyList(),
    val learningContract: LearningContractPayload? = null,
    val conferenceHistory: List<Any?> = emptyList(),
    val classComparisons: ClassComparisonsPayload? = null,
)
