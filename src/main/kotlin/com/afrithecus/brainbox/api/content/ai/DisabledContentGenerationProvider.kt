package com.afrithecus.brainbox.api.content.ai

import com.afrithecus.brainbox.api.common.error.ApiErrorCode
import com.afrithecus.brainbox.api.common.error.ApiException
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

/**
 * Default provider when `app.ai.enabled` is false (the default). It is not a
 * silent fake: any attempt to generate fails loudly with 503 so a deployment
 * without a model key never persists placeholder content.
 */
@Component
@ConditionalOnProperty(name = ["app.ai.enabled"], havingValue = "false", matchIfMissing = true)
class DisabledContentGenerationProvider : ContentGenerationProvider {

    override val name: String = "disabled"

    override fun generate(request: GenerationRequest): GenerationResult {
        throw ApiException(ApiErrorCode.SERVICE_UNAVAILABLE, "content generation is disabled")
    }
}
