package com.afrithecus.brainbox.api.content.ai

/**
 * Provider-neutral content generation contracts (Phase 7.2). The router builds a
 * [GenerationRequest] and a [ContentGenerationProvider] returns a [GenerationResult];
 * only the DeepSeek implementation knows the HTTP wire format.
 */
data class GenerationRequest(
    val generationKey: String,
    val taskType: String,
    val taskTypeLabel: String? = null,
    val conceptCode: String? = null,
    val conceptName: String? = null,
    val subject: String,
    val gradeLevel: String,
    val language: String,
    val standardVersion: String,
    val difficulty: Int? = null,
    val notes: String? = null,
)

data class GenerationResult(
    val body: String? = null,
    val steps: List<GeneratedStep> = emptyList(),
    val questions: List<GeneratedQuestion> = emptyList(),
    val confidence: Double? = null,
    val model: String? = null,
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val sourceUrls: List<String> = emptyList(),
    val license: String? = null,
)

data class GeneratedStep(
    val orderIndex: Int = 0,
    val title: String? = null,
    val body: String? = null,
    val figureSvg: String? = null,
)

data class GeneratedQuestion(
    val orderIndex: Int = 0,
    /** Index into [GenerationResult.steps], when the question belongs to a step. */
    val stepIndex: Int? = null,
    val type: String = "MULTIPLE_CHOICE",
    val text: String = "",
    val options: List<String>? = null,
    val correctAnswer: String? = null,
    val explanation: String? = null,
    val points: Int = 1,
    val difficulty: Int = 3,
    val matchingPairs: Map<String, String>? = null,
)

/**
 * Phase 7.5f independent answer-key verification. A second, separate model
 * interaction that only sees the question stem and its options - never the stored
 * key - and returns its own answer per question. The router compares the two;
 * only a full agreement lets an assessment auto-approve.
 */
data class AnswerVerificationRequest(
    val questions: List<VerificationQuestion> = emptyList(),
)

data class VerificationQuestion(
    val orderIndex: Int = 0,
    val type: String = "MULTIPLE_CHOICE",
    val text: String = "",
    val options: List<String>? = null,
)

data class AnswerVerificationResult(
    val answers: List<VerificationAnswer> = emptyList(),
    val model: String? = null,
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
)

data class VerificationAnswer(
    val orderIndex: Int = 0,
    val answer: String? = null,
)
