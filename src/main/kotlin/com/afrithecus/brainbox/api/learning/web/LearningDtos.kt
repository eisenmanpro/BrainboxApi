package com.afrithecus.brainbox.api.learning.web

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank

// ---------------------------------------------------------------------------
// Learning hub payloads (doc 03 §1/§2).
// ---------------------------------------------------------------------------

data class QuizQuestionRequest(
    @field:NotBlank
    val text: String,
    @field:NotBlank
    val type: String,
    val options: List<String>? = null,
    val correctAnswer: String? = null,
    val explanation: String? = null,
    val points: Int = 1,
    val matchingPairs: Map<String, String>? = null,
)

data class CreateContentRequest(
    @field:NotBlank
    val type: String,
    val title: String? = null,
    val content: String? = null,
    @field:Min(0)
    val durationMinutes: Int = 0,
    val orderIndex: Int? = null,
    val thumbnailUrl: String? = null,
    val quizQuestions: List<QuizQuestionRequest>? = null,
)

data class CreatePostRequest(
    @field:NotBlank
    val title: String,
    @field:NotBlank
    val subject: String,
    val topic: String? = null,
    val subtopic: String? = null,
    val imageUrl: String? = null,
    val description: String? = null,
    @field:Min(1)
    val estimatedMinutes: Int = 5,
    @field:Min(1) @field:Max(5)
    val difficulty: Int = 3,
    val tags: List<String>? = null,
    val scope: String = "GLOBAL",
    val schoolId: String? = null,
    val gradeLevel: String? = null,
    val teacherId: String? = null,
    val isFeatured: Boolean = false,
    val isPublished: Boolean = true,
    val content: List<CreateContentRequest> = emptyList(),
)

data class LearningContentPayload(
    val id: String,
    /** Required by the Android model; blocks were previously unaddressable. */
    val postId: String,
    val type: String,
    val title: String? = null,
    val content: String? = null,
    val durationMinutes: Int,
    val orderIndex: Int,
    val thumbnailUrl: String? = null,
    /** Quiz/flashcard JSON as a **string** (the client model is `String?`, not a JSON tree). */
    val metadata: String? = null,
)

data class LearningPostPayload(
    val id: String,
    val title: String,
    val subject: String,
    val topic: String? = null,
    val subtopic: String? = null,
    val imageUrl: String? = null,
    val description: String? = null,
    val estimatedMinutes: Int,
    val difficulty: Int,
    val tags: List<String>? = null,
    val scope: String,
    val schoolId: String? = null,
    val gradeLevel: String? = null,
    val teacherId: String? = null,
    val isFeatured: Boolean,
    val isPublished: Boolean,
    val viewCount: Int,
    val likeCount: Int,
    val createdAt: Long,
    /** True on the `/trending` rail; the client renders a trending badge. */
    val isTrending: Boolean = false,
    val cbcStrand: String? = null,
    val cbcSubStrand: String? = null,
    val authorName: String = "",
    /** Set when the post subject is outside the client's fixed Subject enum. */
    val customSubjectName: String? = null,
    /** PUBLISHED | SCHEDULED | ARCHIVED; a missing status used to leak archived posts. */
    val status: String = "PUBLISHED",
    val content: List<LearningContentPayload>? = null,
)
