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
    /** Optional at create: a DRAFT may be filled in later; publish refuses an empty paper. */
    val questions: List<CreateContestQuestionRequest> = emptyList(),
    /** GLOBAL | SCHOOL | SCHOOL_GRADE. Admin defaults to GLOBAL; a teacher request is forced to SCHOOL_GRADE. */
    val scope: String? = null,
    val rules: String? = null,
    /** Idempotency key for a retried create (teacher authoring). */
    val clientId: String? = null,
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
    // --- F4 authoring/visibility block ---
    val lifecycle: String = "PUBLISHED",
    val scope: String = "GLOBAL",
    val schoolId: String? = null,
    val schoolName: String? = null,
    val authorName: String? = null,
    val authorRole: String? = null,
    val questionCount: Int = 0,
    val totalPoints: Int = 0,
    val rules: String? = null,
    val resultsPublished: Boolean = false,
    val finalised: Boolean = false,
)

/** One author-visible participant row (registered, maybe submitted). */
data class ContestParticipantPayload(
    val userId: String,
    val name: String,
    val grade: String? = null,
    val registeredAt: Long,
    val submittedAt: Long? = null,
    val score: Int? = null,
    val percentage: Int? = null,
    val integrityFlags: List<String> = emptyList(),
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
