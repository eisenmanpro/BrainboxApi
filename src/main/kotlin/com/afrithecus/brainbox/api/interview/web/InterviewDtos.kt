package com.afrithecus.brainbox.api.interview.web

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank

/**
 * Mock interview payloads. Shapes match the Android models (InterviewModels.kt)
 * and the app's InterviewApi; enum-valued fields carry enum names.
 */

data class InterviewQuestionPayload(
    val id: String,
    val text: String,
    val type: String,
    val order: Int,
    val sampleAnswer: String? = null,
    val keywords: List<String> = emptyList(),
    val minWords: Int = 20,
    val maxWords: Int = 200,
    val structurePhrases: List<String> = emptyList(),
    val rubricType: String = "NONE",
    val companyFocus: String? = null,
    val schoolFocus: String? = null,
)

data class InterviewSessionPayload(
    val id: String,
    val type: String,
    val mode: String,
    val questions: List<InterviewQuestionPayload>,
    val startedAt: Long,
    val completedAt: Long? = null,
)

data class StartInterviewRequest(
    @field:NotBlank val type: String,
    @field:NotBlank val userId: String,
    @field:NotBlank val mode: String,
    @field:Min(1) @field:Max(5) val difficulty: Int = 1,
)

data class SubmitAnswerRequest(
    @field:NotBlank val sessionId: String,
    @field:NotBlank val questionId: String,
    val transcribedText: String = "",
    val audioUrl: String? = null,
    val durationSeconds: Int? = null,
    /** Optional on-device emotion metadata (doc 06 §2.9); stored for analytics. */
    val primaryEmotion: String? = null,
    val emotionConfidence: Double? = null,
    val emotionMetrics: Map<String, Double>? = null,
)

data class RubricCriterionPayload(
    val name: String,
    val score: Int,
    val maxScore: Int,
    val feedback: String = "",
    val description: String = "",
)

data class RubricScorePayload(
    val type: String,
    val criteria: List<RubricCriterionPayload>,
    val overallScore: Int,
)

data class InterviewAnswerPayload(
    val questionId: String,
    val transcribedText: String,
    val clarityScore: Int = 0,
    val fillerWordCount: Int = 0,
    val fillerReplacements: Map<String, String> = emptyMap(),
    val pace: Int = 0,
    val keywordMatchCount: Int = 0,
    val totalKeywords: Int = 0,
    val structurePhrasesFound: Int = 0,
    val wordCount: Int = 0,
    val feedback: String = "",
    val score: Int = 0,
    val rubricScore: RubricScorePayload? = null,
    val dictionScore: Int = 0,
    val pronunciationScore: Int = 0,
)

data class InterviewResultPayload(
    val sessionId: String,
    val overallScore: Int,
    val answers: List<InterviewAnswerPayload>,
    val completedAt: Long,
)

data class PastAttemptPayload(
    val id: String,
    val date: Long,
    val type: String,
    val mode: String,
    val score: Int,
    val durationMinutes: Int,
)

data class CategoryPerformancePayload(val category: String, val averageScore: Double)

data class InterviewTrendPointPayload(
    val timestamp: Long,
    val score: Double,
    val clarity: Double,
    val pace: Double,
    val fillerCount: Int,
)

data class InterviewAnalyticsPayload(
    val totalSessions: Int,
    val averageScore: Double,
    val improvementTrend: Double,
    val categoryPerformance: List<CategoryPerformancePayload>,
    val commonWeaknesses: List<String>,
    val emotionDistribution: Map<String, Int> = emptyMap(),
    val trendPoints: List<InterviewTrendPointPayload> = emptyList(),
)
