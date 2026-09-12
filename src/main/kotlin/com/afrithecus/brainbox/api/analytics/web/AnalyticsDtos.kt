package com.afrithecus.brainbox.api.analytics.web

/**
 * Analytics payloads. Field names/shapes match the Android analytics models
 * (models/analytics/AnalyticsModels.kt and models/ExamSessionModels.kt) exactly.
 * All values are server-computed; the client renders them read-only (doc 02 §8).
 */

data class StudentPayload(
    val id: String,
    val name: String,
    val className: String,
    val admissionNumber: String,
    val avatarUrl: String? = null,
)

data class SubjectPerformancePayload(
    val subject: String,
    val score: Double,
    val grade: String,
    val percentage: Double,
    val trend: List<Double> = emptyList(),
)

data class AnalyticsTopicPerformancePayload(
    val topic: String,
    val score: Double,
    val masteryLevel: String,
)

data class AnalyticsQuestionResultPayload(
    val questionId: String,
    val questionText: String,
    val isCorrect: Boolean,
    val scoreAwarded: Int,
    val pointsPossible: Int,
    val requiresExplanation: Boolean,
    val isKeyQuestion: Boolean,
    val cbcStrand: String?,
)

data class AnalyticsWeakAreaPayload(
    val cbcStrand: String,
    val masteryLevel: Double,
    val severity: String,
    val affectedStudents: Int,
    val recommendedAction: String,
)

/** Mirrors com.afrithecus.brainbox.models.ExamResult (answer keys excluded). */
data class AnalyticsExamResultPayload(
    val id: String,
    val title: String,
    val score: Int,
    val percentage: Double,
    val totalPoints: Int,
    val percentile: Int,
    val topicBreakdown: Map<String, Float>,
    val timePerQuestion: Map<Int, Long>,
    val status: String,
    val markingType: String,
    val gradingDetails: List<AnalyticsQuestionResultPayload>,
    val weakAreas: List<AnalyticsWeakAreaPayload>,
    val autoGradedScore: Int,
    val pendingReviewScore: Int,
)

data class StudentPerformancePayload(
    val student: StudentPayload,
    val gradeLevel: String = "",
    val overallPercentage: Double,
    val overallGrade: String,
    val previousPercentage: Double? = null,
    val subjects: List<SubjectPerformancePayload>,
    val strengths: List<String>,
    val weaknesses: List<String>,
    val recommendedActions: List<String>,
    val examHistory: List<AnalyticsExamResultPayload>,
)

data class SubjectDetailPayload(
    val studentId: String,
    val subject: String,
    val overallGrade: String,
    val overallScore: Double,
    val examHistory: List<AnalyticsExamResultPayload>,
    val topicBreakdown: List<AnalyticsTopicPerformancePayload>,
    val recommendedActions: List<String>,
)

data class SubjectClassPerformancePayload(
    val subject: String,
    val classMean: Double,
    val topScore: Int,
    val bottomScore: Int,
    val passRate: Double,
)

data class StudentGradePayload(
    val studentId: String,
    val studentName: String,
    val overallPercentage: Double,
    val overallGrade: String,
    val rank: Int? = null,
)

data class ClassAnalyticsPayload(
    val classId: String,
    val className: String,
    val totalStudents: Int,
    val classAverage: Double,
    val gradeDistribution: Map<String, Int>,
    val subjectPerformance: List<SubjectClassPerformancePayload>,
    val students: List<StudentGradePayload>,
    val examHistory: List<AnalyticsExamResultPayload>,
)
