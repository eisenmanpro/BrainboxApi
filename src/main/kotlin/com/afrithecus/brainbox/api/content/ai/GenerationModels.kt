package com.afrithecus.brainbox.api.content.ai

import tools.jackson.databind.JsonNode

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
    /**
     * Subject-agent persona appended to the provider system prompt (Phase 7.5
     * subject agents). Provider-neutral: the router resolves it from the subject,
     * so a provider only has to append it.
     */
    val persona: String? = null,
    /**
     * Phase 7.5 scope inheritance: GLOBAL (default), SCHOOL or SCHOOL_GRADE_CLASS.
     * The router stamps it on the unit and the projection on the client row.
     */
    val scope: String? = null,
    /**
     * Curriculum grounding resolved through the concept_lookup tool: the strand,
     * sub-strand and learning outcome for the concept, appended to the generation
     * prompt so every unit is anchored in the seeded CBC catalogue.
     */
    val curriculumContext: String? = null,
)

data class GenerationResult(
    val body: String? = null,
    val steps: List<GeneratedStep> = emptyList(),
    val questions: List<GeneratedQuestion> = emptyList(),
    val confidence: Double? = null,
    val model: String? = null,
    /** The provider that served the call; set by the concrete provider. */
    val provider: String? = null,
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    /** Priced cost of the call in micros; 0 when the provider has no price configured. */
    val costMicros: Long = 0L,
    val sourceUrls: List<String> = emptyList(),
    val license: String? = null,
)

data class GeneratedStep(
    val orderIndex: Int = 0,
    val title: String? = null,
    val body: String? = null,
    /** Declarative figure spec (Phase 7.5); the server renders it to SVG. */
    val figure: JsonNode? = null,
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
    /** Optional figure spec shown with the question; server-rendered to SVG. */
    val figure: JsonNode? = null,
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
    /** The provider that served the verification call; set by the concrete provider. */
    val provider: String? = null,
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    /** Priced cost of the call in micros; 0 when the provider has no price configured. */
    val costMicros: Long = 0L,
)

data class VerificationAnswer(
    val orderIndex: Int = 0,
    val answer: String? = null,
)

/**
 * Phase 7.5 LLM critic. A separate model interaction that judges the pedagogy of an
 * assembled unit (clarity, accuracy, age-appropriateness) and returns a 0..1 score
 * plus structured findings. It is a quality signal, not the deterministic safety
 * filter.
 */
data class ContentCritiqueRequest(
    val taskType: String,
    val subject: String,
    val gradeLevel: String,
    val title: String? = null,
    val body: String? = null,
    val steps: List<String> = emptyList(),
    val questions: List<String> = emptyList(),
)

data class CritiqueFinding(
    val severity: String = "WARNING",
    val code: String = "",
    val message: String = "",
)

data class CritiqueResult(
    val score: Double? = null,
    val findings: List<CritiqueFinding> = emptyList(),
    val model: String? = null,
    val provider: String? = null,
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val costMicros: Long = 0L,
)
