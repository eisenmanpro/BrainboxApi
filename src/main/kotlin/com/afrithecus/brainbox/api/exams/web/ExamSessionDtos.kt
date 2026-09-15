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

/**
 * Per-question grading detail (doc 02 §3.3). Exposes correctness and points
 * only; correctAnswer/explanation never leave the server.
 */
data class QuestionGradingDetailPayload(
    val questionId: String,
    val questionText: String,
    val isCorrect: Boolean,
    val scoreAwarded: Int,
    val pointsPossible: Int,
    val requiresExplanation: Boolean,
    val isKeyQuestion: Boolean,
    val cbcStrand: String? = null,
)

/** A topic the student has not yet mastered (doc 02 §3.3). */
data class ExamWeakAreaPayload(
    val cbcStrand: String,
    val masteryLevel: Double,
    val severity: String,
    val affectedStudents: Int,
    val recommendedAction: String,
)

/**
 * Exam result (doc 02 §3.3) — mirrors
 * com.afrithecus.brainbox.models.ExamResult exactly. `percentage` is 0..100 and
 * `topicBreakdown` values are 0..1 fractions, the scale the client renders.
 */
data class ExamResultPayload(
    val id: String,
    val title: String,
    val score: Int,
    val percentage: Double,
    val totalPoints: Int,
    val percentile: Int,
    val topicBreakdown: Map<String, Double>,
    val timePerQuestion: Map<Int, Long>,
    val status: String,
    val markingType: String,
    val gradingDetails: List<QuestionGradingDetailPayload>,
    val weakAreas: List<ExamWeakAreaPayload>,
    val autoGradedScore: Int,
    val pendingReviewScore: Int,
)

/**
 * Exam-hub submission detail for post-exam review (doc 02 §3.4) — mirrors
 * com.afrithecus.brainbox.models.ExamSubmissionDetails. Questions are key-free.
 */
data class ExamSubmissionDetailsPayload(
    val examId: String,
    val title: String,
    val questions: List<QuestionPayload>,
    val userAnswers: Map<String, String>? = null,
    val submittedAt: Long,
    val status: String,
    val markingType: String,
)

/** Practice-paper attempt recorded from the client's self-graded result (doc 02 §5.3). */
data class PracticePaperAttemptRequest(
    @field:Min(0)
    val score: Int,
    @field:Min(1)
    val totalPoints: Int,
    @field:Min(0) @field:Max(100)
    val percentage: Int,
    val submittedAt: Long? = null,
)

/** Source descriptor the Android DocumentSource sealed type maps onto. */
data class DocumentSourcePayload(
    /** REMOTE | ASSET | LOCAL_FILE */
    val type: String = "REMOTE",
    val path: String? = null,
    val url: String? = null,
)

/**
 * Practice-paper listing item (doc 02 §5.1). Field names mirror the Android `DocumentItem`
 * (models/DocumentModels.kt) so the client maps the payload directly.
 */
data class DocumentItem(
    val id: String,
    val title: String,
    val author: String = "",
    val description: String = "",
    /** Android DocumentType: PDF | EPUB | PLAINTEXT. */
    val type: String = "PDF",
    val source: DocumentSourcePayload,
    val coverUrl: String? = null,
    val pageCount: Int? = null,
    val fileSizeBytes: Long? = null,
    val isBundled: Boolean = false,
    val addedAt: Long,
    val code: String? = null,
    val isPracticePaper: Boolean = true,
    val grade: String? = null,
    val subject: String? = null,
    val isLocked: Boolean = false,
    val assignmentNotice: String? = null,
    val scope: String = "GLOBAL",
    val schoolId: String? = null,
    val durationMinutes: Int? = null,
    val questionCount: Int? = null,
    val examYear: Int? = null,
    val isMcp: Boolean = false,
)
