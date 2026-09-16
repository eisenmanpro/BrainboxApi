package com.afrithecus.brainbox.api.content.ai

/** One failed candidate attempt inside the routing provider, kept for the trace. */
data class ProviderAttempt(
    val provider: String,
    val latencyMs: Long,
    val error: String?,
)

/**
 * Raised when every candidate provider failed one routed call. [attempts] is the
 * ordered attempt list, so the capture writer records a failed `model_call` per
 * provider and per-provider error accounting stays honest instead of collapsing
 * to the router.
 */
class ProviderCallException(val attempts: List<ProviderAttempt>) : RuntimeException(
    attempts.lastOrNull()?.let { "provider '" + it.provider + "' failed: " + (it.error ?: "unknown error") }
        ?: "no provider available",
) {
    val lastProvider: String? get() = attempts.lastOrNull()?.provider
}
