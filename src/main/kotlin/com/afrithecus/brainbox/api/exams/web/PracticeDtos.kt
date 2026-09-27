package com.afrithecus.brainbox.api.exams.web

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

/**
 * A learner's request for a personal practice paper (§B7). `difficulty` and
 * `questionCount` are suggestions: the server clamps them and picks the difficulty
 * itself when the learner does not care.
 */
data class PracticeGenerateRequest(
    @field:NotBlank
    @field:Size(max = 128)
    val subject: String,

    @field:Size(max = 128)
    val topic: String? = null,

    @field:Min(1)
    @field:Max(5)
    val difficulty: Int? = null,

    @field:Min(1)
    @field:Max(20)
    val questionCount: Int? = null,
)

/**
 * One personal practice paper as the learner, their teacher or their guardian sees it.
 * Scores are rounded percentages; `bestPercentage` is null until the paper is attempted.
 */
data class PracticePaperPayload(
    val examId: String,
    val title: String,
    val subject: String,
    val topic: String? = null,
    val questionCount: Int,
    val difficulty: Int,
    val durationMinutes: Int,
    val totalPoints: Int,
    val createdAt: Long,
    val attempts: Int,
    val bestPercentage: Int? = null,
)
