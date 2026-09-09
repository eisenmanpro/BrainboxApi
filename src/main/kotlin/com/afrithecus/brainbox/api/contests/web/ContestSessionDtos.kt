package com.afrithecus.brainbox.api.contests.web

import com.afrithecus.brainbox.api.exams.web.QuestionPayload
import com.afrithecus.brainbox.api.exams.web.QuestionResultPayload
import tools.jackson.databind.JsonNode

/** Live contest session (doc 05 §1.5). */
data class ContestSessionResponse(
    val id: String,
    val contestId: String,
    val questions: List<QuestionPayload>,
    val currentQuestionIndex: Int,
    val answers: JsonNode? = null,
    val startedAt: Long,
    val timeRemainingSeconds: Int,
    val status: String,
)

/** Server-graded contest result with server-computed integrity (doc 05 §1.6). */
data class ContestResultPayload(
    val contestId: String,
    val userId: String,
    val score: Int,
    val totalPoints: Int,
    val percentage: Int,
    val correctAnswers: Int,
    val totalQuestions: Int,
    val timeTakenSeconds: Int,
    val submittedAt: Long,
    val integrityScore: Int,
    val integrityFlags: List<String>,
    val answers: JsonNode? = null,
    val questionResults: List<QuestionResultPayload>,
)
