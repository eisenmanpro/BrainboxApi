package com.afrithecus.brainbox.api.content.ai

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Phase 7.2 generation settings. Disabled by default so the API boots and serves
 * the cache without a model key; enable with `AI_ENABLED=true` and at least one
 * provider key. The Spring AI DeepSeek autoconfiguration stays excluded because
 * the providers own the HTTP wire format.
 *
 * `deepseek` is always available once [enabled] is true; `openai` is a generic
 * OpenAI-compatible endpoint that joins the routing pool when its own
 * [Provider.enabled] is true.
 */
@ConfigurationProperties(prefix = "app.ai")
data class AppAiProperties(
    val enabled: Boolean = false,
    val routing: Routing = Routing(),
    val deepseek: Provider = Provider(),
    val openai: Provider = Provider(),
) {

    /** Routing policy for the provider pool (cost, latency, health). */
    data class Routing(
        val policy: RoutingPolicy = RoutingPolicy.CHEAPEST,
        /** Consecutive failures before a provider is demoted to the back of the pool. */
        val failureThreshold: Int = 3,
        /** How long a demoted provider stays at the back before it is trusted again. */
        val cooldownSeconds: Long = 120,
    )

    /**
     * One OpenAI-compatible endpoint. Base URL, key, model and price are all that
     * distinguish the vendors; an absent price means "unknown" and never wins a
     * cost comparison.
     */
    data class Provider(
        val enabled: Boolean = false,
        val apiKey: String = "",
        val baseUrl: String = "",
        val model: String = "",
        /**
         * Phase 7.5f: model used for independent answer-key verification. Empty
         * means "use [model]", so verification defaults to the same engine as
         * generation; the knob exists so a different model can be pointed at it
         * without touching the router.
         */
        val verifyModel: String = "",
        val promptMicrosPerMillion: Long = 0,
        val completionMicrosPerMillion: Long = 0,
    )
}
