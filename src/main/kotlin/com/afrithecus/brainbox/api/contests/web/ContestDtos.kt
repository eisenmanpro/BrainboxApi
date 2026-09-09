package com.afrithecus.brainbox.api.contests.web

import com.afrithecus.brainbox.api.exams.web.QuestionPayload
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty

// ---------------------------------------------------------------------------
// Contests payloads (doc 05 §1).
// ---------------------------------------------------------------------------

data class CreateContestQuestionRequest(
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

data class CreateContestRequest(
    @field:NotBlank
    val title: String,
    @field:NotBlank
    val subject: String,
    @field:NotBlank
    val grade: String,
    val startTime: Long,
    val endTime: Long,
    @field:Min(0)
    val entryFee: Int = 0,
    val prize: String? = null,
    @field:Min(1)
    val maxParticipants: Int? = null,
    @field:Min(1) @field:Max(5)
    val difficulty: Int = 3,
    @field:NotEmpty
    val questions: List<CreateContestQuestionRequest>,
)

/** Contest row (doc 05 §1.1); questions included only where the flow reveals them. */
data class ContestPayload(
    val id: String,
    val title: String,
    val subject: String,
    val grade: String,
    val status: String,
    val startTime: Long,
    val endTime: Long,
    val entryFee: Int,
    val prize: String? = null,
    val maxParticipants: Int? = null,
    val registeredCount: Int,
    val questions: List<QuestionPayload>? = null,
    val userRank: Int? = null,
    val userScore: Int? = null,
    val isUserRegistered: Boolean,
)

data class RegistrationResponse(
    val success: Boolean,
    val message: String,
    val registrationId: String,
)

data class LeaderboardEntry(
    val rank: Int,
    val studentName: String,
    val score: Int,
)

data class LeaderboardPayload(
    val contestId: String,
    val entries: List<LeaderboardEntry>,
    val userEntry: LeaderboardEntry? = null,
)
