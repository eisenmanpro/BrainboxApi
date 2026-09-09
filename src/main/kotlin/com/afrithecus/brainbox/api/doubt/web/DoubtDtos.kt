package com.afrithecus.brainbox.api.doubt.web

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

// ---------------------------------------------------------------------------
// Doubt solving payloads (doc 05 §3).
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
    val voteCount: Int,
    val viewCount: Int,
    val createdAt: Long,
)

data class DoubtAnswerPayload(
    val id: String,
    val questionId: String,
    val body: String,
    val authorId: String,
    val authorName: String,
    val authorRole: String,
    val isAccepted: Boolean,
    val voteCount: Int,
    val createdAt: Long,
)
