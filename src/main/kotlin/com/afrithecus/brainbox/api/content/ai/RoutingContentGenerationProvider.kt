package com.afrithecus.brainbox.api.content.ai

import org.slf4j.LoggerFactory

/**
 * The provider pool behind the {@link ContentGenerationProvider} seam. It is the
 * single bean the router calls (marked `@Primary`) and the only place a provider
 * is chosen for a call.
 *
 * Ordering is the routing policy (cheapest, fastest or declared) applied to the
 * healthy candidates, with a provider that is serving out a failure cooldown moved
 * behind the healthy ones. A call walks the ordered pool until one provider
 * succeeds, so a transient outage of the preferred provider does not fail the job;
 * if every provider fails, the ordered attempt list is raised in
 * {@link ProviderCallException} for the capture writer.
 *
 * The provider that served the call reports itself on the result (`provider`), so
 * the caller prices and attributes the `model_call` to the real vendor, not the
 * router.
 */
class RoutingContentGenerationProvider(
    candidates: List<RoutedGenerationProvider>,
    private val health: ProviderHealth,
    private val policy: RoutingPolicy,
) : ContentGenerationProvider {

    private val log = LoggerFactory.getLogger(javaClass)

    private val candidates: List<RoutedGenerationProvider> = candidates.toList()

    override val name: String = ROUTER_NAME

    init {
        require(this.candidates.isNotEmpty()) {
            "app.ai.enabled is true but no OpenAI-compatible provider is configured"
        }
    }

    override fun generate(request: GenerationRequest): GenerationResult =
        route("generate") { it.generate(request) }

    override fun verifyAnswerKeys(request: AnswerVerificationRequest): AnswerVerificationResult =
        route("verify") { it.verifyAnswerKeys(request) }

    private fun <T> route(operation: String, call: (RoutedGenerationProvider) -> T): T {
        val attempts = mutableListOf<ProviderAttempt>()
        for (candidate in ordered()) {
            val startedAt = System.nanoTime()
            try {
                val result = call(candidate)
                health.recordSuccess(candidate.name, elapsedMillis(startedAt))
                return result
            } catch (failure: Exception) {
                val latencyMs = elapsedMillis(startedAt)
                health.recordFailure(candidate.name)
                log.warn("provider {} failed during {}: {}", candidate.name, operation, failure.message)
                attempts += ProviderAttempt(candidate.name, latencyMs, failure.message)
            }
        }
        throw ProviderCallException(attempts)
    }

    /**
     * Healthy candidates first, ranked by the policy, then the demoted ones, also
     * ranked, so a total outage of the preferred provider is still served by the
     * next one rather than failing without a call.
     */
    private fun ordered(): List<RoutedGenerationProvider> {
        if (candidates.size == 1) return candidates
        val (demoted, healthy) = candidates.partition { health.isDemoted(it.name) }
        return ranked(healthy) + ranked(demoted)
    }

    private fun ranked(pool: List<RoutedGenerationProvider>): List<RoutedGenerationProvider> =
        when (policy) {
            RoutingPolicy.CONFIGURED -> pool
            RoutingPolicy.CHEAPEST -> pool.sortedWith(
                compareBy({ it.costProfile.blendedMicrosPerMillion }, { latency(it) }),
            )
            RoutingPolicy.FASTEST -> pool.sortedWith(
                compareBy({ latency(it) }, { it.costProfile.blendedMicrosPerMillion }),
            )
        }

    private fun latency(candidate: RoutedGenerationProvider): Long =
        health.observedLatencyMs(candidate.name) ?: Long.MAX_VALUE

    private fun elapsedMillis(startedAt: Long): Long = (System.nanoTime() - startedAt) / 1_000_000L

    private companion object {
        /** The seam name; the served provider is reported on each result instead. */
        const val ROUTER_NAME = "router"
    }
}
