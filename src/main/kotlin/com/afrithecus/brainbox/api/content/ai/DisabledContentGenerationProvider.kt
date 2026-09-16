package com.afrithecus.brainbox.api.content.ai

import com.afrithecus.brainbox.api.common.error.ApiErrorCode
import com.afrithecus.brainbox.api.common.error.ApiException

/**
 * Default provider when `app.ai.enabled` is false (the default). It is not a
 * silent fake: any attempt to generate fails loudly with 503 so a deployment
 * without a model key never persists placeholder content. Wired as a bean by
 * [AiProviderConfig] rather than scanned, so the enabled and disabled beans can
 * never both register.
 */
class DisabledContentGenerationProvider : ContentGenerationProvider {

    override val name: String = "disabled"

    override fun generate(request: GenerationRequest): GenerationResult {
        throw ApiException(ApiErrorCode.SERVICE_UNAVAILABLE, "content generation is disabled")
    }

    /** Phase 7.5f: verify through the same disabled seam, so nothing auto-approves. */
    override fun verifyAnswerKeys(request: AnswerVerificationRequest): AnswerVerificationResult {
        throw ApiException(ApiErrorCode.SERVICE_UNAVAILABLE, "answer-key verification is disabled")
    }
}
