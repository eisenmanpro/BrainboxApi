package com.afrithecus.brainbox.api.content.ai

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Phase 7.2 generation settings. Disabled by default so the API boots and serves
 * the cache without a model key; enable with `AI_ENABLED=true` and set
 * `DEEPSEEK_API_KEY`. The Spring AI DeepSeek autoconfiguration stays excluded
 * because the provider owns the HTTP wire format.
 */
@ConfigurationProperties(prefix = "app.ai")
data class AppAiProperties(
    val enabled: Boolean = false,
    val deepseek: DeepSeek = DeepSeek(),
) {
    data class DeepSeek(
        val apiKey: String = "",
        val baseUrl: String = "https://api.deepseek.com",
        val model: String = "deepseek-chat",
        /**
         * Phase 7.5f: model used for independent answer-key verification. Empty
         * means "use [model]", so verification defaults to the same engine as
         * generation; the knob exists so a different model can be pointed at it
         * without touching the router.
         */
        val verifyModel: String = "",
    )
}
