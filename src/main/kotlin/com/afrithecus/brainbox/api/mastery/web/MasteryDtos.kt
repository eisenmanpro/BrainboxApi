package com.afrithecus.brainbox.api.mastery.web

import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank

/** Mastery payloads matching the Android MasteryModels exactly. */

data class TopicMasteryPayload(
    val topicId: String,
    val topicName: String,
    val subject: String,
    val score: Float,
    val level: String,
    val attemptsCount: Int,
    val lastPracticed: Long,
    val questionsAttempted: Int,
    val correctAnswers: Int,
    val averageTimePerQuestion: Float,
    val trend: Float,
    val recommendedNextTopic: String? = null,
    val prerequisiteTopics: List<String> = emptyList(),
)

data class SubjectMasterySummaryPayload(
    val subject: String,
    val overallScore: Float,
    val topicsMastered: Int,
    val totalTopics: Int,
    val weakestTopic: TopicMasteryPayload? = null,
    val strongestTopic: TopicMasteryPayload? = null,
    val recommendedFocus: String,
)

data class MasteryOverviewPayload(
    val overallMastery: Float,
    val totalTopics: Int,
    val masteredCount: Int,
    val proficientCount: Int,
    val developingCount: Int,
    val noviceCount: Int,
    val subjectsSummary: List<SubjectMasterySummaryPayload>,
    val recentImprovements: List<TopicMasteryPayload>,
    val strugglingTopics: List<TopicMasteryPayload>,
)

data class MasteryUpdateRequest(
    @field:NotBlank val topicId: String,
    @field:Min(0) val correctAnswers: Int,
    @field:Min(1) val totalQuestions: Int,
    @field:Min(0) val timeSpentSeconds: Int,
)
