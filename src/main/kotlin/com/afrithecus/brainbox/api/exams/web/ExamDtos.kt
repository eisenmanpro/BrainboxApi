package com.afrithecus.brainbox.api.exams.web

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.NotNull

// ---------------------------------------------------------------------------
// Exams payloads (doc 02 §2/§4). Question key material is nullable and only
// ever populated for authoring/admin views; student payloads keep it null so
// the global non_null Jackson inclusion omits it entirely.
// ---------------------------------------------------------------------------

data class CreateExamQuestionRequest(
    @field:NotBlank
    val text: String,
    @field:NotBlank
    val type: String,
    val options: List<String>? = null,
    val correctAnswer: String? = null,
    val explanation: String? = null,
    @field:Min(0)
    val points: Int = 1,
    @field:Min(1) @field:Max(5)
    val difficulty: Int = 3,
    val matchingPairs: Map<String, String>? = null,
    val topic: String? = null,
    val subtopic: String? = null,
)

data class CreateExamRequest(
    @field:NotBlank
    val title: String,
    @field:NotBlank
    val subject: String,
    @field:NotBlank
    val examType: String,
    val scope: String = "GLOBAL",
    val schoolId: String? = null,
    @field:Min(1)
    val durationMinutes: Int,
    @field:Min(1) @field:Max(5)
    val difficulty: Int = 3,
    val examYear: Int? = null,
    val isMcp: Boolean = false,
    val coverImageUrl: String? = null,
    val isPublished: Boolean = true,
    @field:NotEmpty
    val questions: List<CreateExamQuestionRequest>,
)

data class QuestionPayload(
    val id: String,
    val text: String,
    val type: String,
    val options: List<String>? = null,
    val correctAnswer: String? = null,
    val explanation: String? = null,
    val points: Int,
    val difficulty: Int,
    val matchingPairs: Map<String, String>? = null,
    val topic: String? = null,
    val subtopic: String? = null,
)

/** Hub card (doc 02 §2.2). */
data class ExamCard(
    val id: String,
    val title: String,
    val subject: String,
    val durationMinutes: Int,
    val questionCount: Int,
    val difficulty: Int,
    val status: String,
    val isPracticePaper: Boolean,
    val examYear: Int? = null,
    val coverImageUrl: String? = null,
    val averageScore: Int? = null,
    val completedAt: Long? = null,
)

/** Hub state counters (doc 02 §2.1). */
data class HubState(
    val availableCount: Int,
    val inProgressCount: Int,
    val completedCount: Int,
    val quizzesCount: Int,
    val savedCount: Int,
    val practicePapersCount: Int,
)

/** Listing row for GET /exams (doc 02 §2.3). */
data class ExamSummary(
    val id: String,
    val title: String,
    val subject: String,
    val durationMinutes: Int,
    val questionCount: Int,
    val difficulty: Int,
    val status: String,
    val isPracticePaper: Boolean,
    val examYear: Int? = null,
    val coverImageUrl: String? = null,
    val averageScore: Int? = null,
    val studentsTaken: Int = 0,
)

/** Exam detail with questions (doc 02 §2.4); keys only in authoring context. */
data class ExamDetail(
    val id: String,
    val title: String,
    val subject: String,
    val durationMinutes: Int,
    val questionCount: Int,
    val difficulty: Int,
    val status: String,
    val questions: List<QuestionPayload>,
    val createdBy: String,
    val createdAt: Long,
    val isPublished: Boolean,
    val averageScore: Int = 0,
    val studentsTaken: Int = 0,
)
