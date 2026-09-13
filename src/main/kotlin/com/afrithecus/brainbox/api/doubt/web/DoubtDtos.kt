package com.afrithecus.brainbox.api.doubt.web

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

// ---------------------------------------------------------------------------
// Doubt solving payloads (doc 05 §3). Shapes mirror the Android client models
// in DoubtModels.kt: votes are split into upvotes/downvotes plus the caller's
// own direction, and bookmarks/accepted-answer state travel with the question.
// ---------------------------------------------------------------------------

data class AskQuestionRequest(
    @field:NotBlank
    @field:Size(max = 255)
    val title: String,
    @field:NotBlank
    val body: String,
    @field:NotBlank
    val subject: String,
    val tags: List<String>? = null,
)

data class AnswerRequest(
    @field:NotBlank
    val body: String,
)

data class DoubtQuestionPayload(
    val id: String,
    val title: String,
    val body: String,
    val subject: String,
    val tags: List<String>? = null,
    val authorId: String,
    val authorName: String,
    val status: String,
    val upvotes: Int,
    val downvotes: Int,
    val answerCount: Int,
    val viewCount: Int,
    val createdAt: Long,
    val isAcceptedAnswer: Boolean,
    val acceptedAnswerId: String? = null,
    val currentUserVote: Int = 0,
    val isBookmarked: Boolean = false,
)

data class DoubtAnswerPayload(
    val id: String,
    val questionId: String,
    val body: String,
    val authorId: String,
    val authorName: String,
    val authorRole: String,
    val isAccepted: Boolean,
    val upvotes: Int,
    val downvotes: Int,
    val currentUserVote: Int = 0,
    val createdAt: Long,
)

data class VoteResultPayload(
    val success: Boolean,
    val newUpvotes: Int,
    val newDownvotes: Int,
    val message: String,
)
