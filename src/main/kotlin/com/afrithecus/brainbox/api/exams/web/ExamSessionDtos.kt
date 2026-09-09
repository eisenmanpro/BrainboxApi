package com.afrithecus.brainbox.api.exams.web

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotNull
import tools.jackson.databind.JsonNode

/** Live exam session (doc 02 §3.1). */
data class ExamSessionResponse(
    val id: String,
    val examId: String,
    val title: String,
    val questions: List<QuestionPayload>,
    val currentQuestionIndex: Int,
    val answers: JsonNode? = null,
    val startedAt: Long,
    val timeRemainingSeconds: Int,
    val status: String,
    val flaggedQuestions: List<String>? = null,
)

/** Per-question grading detail returned after submission (doc 02 §3.3). */
data class QuestionResultPayload(
    val questionId: String,
    val isCorrect: Boolean,
    val userAnswer: String? = null,
    val correctAnswer: String? = null,
    val explanation: String? = null,
    val pointsEarned: Int,
)

/** Mastery deltas (populated once mastery tracking integrates; empty today). */
data class MasteryUpdatePayload(
    val topicId: String,
    val correctAnswers: Int,
    val totalQuestions: Int,
    val timeSpentSeconds: Int,
)

/** Exam result (doc 02 §3.3). */
data class ExamResultPayload(
    val id: String,
    val examId: String,
    val userId: String,
    val score: Int,
    val totalPoints: Int,
    val percentage: Int,
    val grade: String? = null,
    val correctAnswers: Int,
    val totalQuestions: Int,
    val timeTakenSeconds: Int,
    val submittedAt: Long,
    val answers: JsonNode? = null,
    val questionResults: List<QuestionResultPayload>,
    val masteryUpdates: List<MasteryUpdatePayload> = emptyList(),
)

/** Past-paper attempt recorded from the client's self-graded result (doc 02 §5.3). */
data class PastPaperAttemptRequest(
    @field:Min(0)
    val score: Int,
    @field:Min(1)
    val totalPoints: Int,
    @field:Min(0) @field:Max(100)
    val percentage: Int,
    val submittedAt: Long? = null,
)

/** Past-paper listing item (doc 02 §5.1). */
data class DocumentItem(
    val id: String,
    val title: String,
    val subject: String,
    val durationMinutes: Int,
    val questionCount: Int,
    val examYear: Int? = null,
    val isMcp: Boolean = false,
    val isPastPaper: Boolean = true,
    val iconUrl: String? = null,
)
