package com.afrithecus.brainbox.api.content.ai

/** The generation provider seam (Phase 7.2); [ContentRouter] is its only caller. */
interface ContentGenerationProvider {

    val name: String

    fun generate(request: GenerationRequest): GenerationResult

    /**
     * Phase 7.5f: answer each question independently, without the stored key, so
     * the router can measure agreement. The router is the only caller; a provider
     * that cannot verify must fail closed (throw) rather than return an empty set.
     */
    fun verifyAnswerKeys(request: AnswerVerificationRequest): AnswerVerificationResult
}
