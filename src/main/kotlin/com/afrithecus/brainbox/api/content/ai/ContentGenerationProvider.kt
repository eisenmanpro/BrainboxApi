package com.afrithecus.brainbox.api.content.ai

import com.afrithecus.brainbox.api.common.error.ApiErrorCode
import com.afrithecus.brainbox.api.common.error.ApiException

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

    /**
     * Phase 7.5 LLM critic: judge the pedagogy of an assembled unit. A provider that
     * cannot critique must fail closed (throw) rather than return a passing score, so
     * the default is an explicit error.
     */
    fun critique(request: ContentCritiqueRequest): CritiqueResult =
        throw ApiException(ApiErrorCode.SERVICE_UNAVAILABLE, "pedagogy critique is not supported by this provider")
}
