package com.afrithecus.brainbox.api.content.ai

/**
 * A provider's price, in micros (1e-6 currency units) per million tokens. The
 * routing provider orders candidates by it and the concrete provider prices each
 * call from it, so one value drives both the routing decision and the stored
 * `model_calls.cost_micros`.
 */
data class ProviderCostProfile(
    val promptMicrosPerMillion: Long,
    val completionMicrosPerMillion: Long,
) {

    /** The cost of one call in micros, rounded down to the whole micro. */
    fun costMicros(promptTokens: Int, completionTokens: Int): Long =
        (promptTokens.toLong() * promptMicrosPerMillion +
            completionTokens.toLong() * completionMicrosPerMillion) / MICROS_PER_MILLION

    /**
     * A single blended figure for ordering two providers of unknown token mix: the
     * prompt price plus the completion price. Only the ordering matters, so the
     * absolute value has no unit.
     */
    val blendedMicrosPerMillion: Long
        get() = if (this == UNKNOWN) Long.MAX_VALUE else promptMicrosPerMillion + completionMicrosPerMillion

    companion object {
        private const val MICROS_PER_MILLION = 1_000_000L

        /**
         * Unknown pricing: still usable, but it never wins a cost comparison and
         * never prices a call (the router falls back to the shared default price).
         */
        val UNKNOWN = ProviderCostProfile(Long.MAX_VALUE / 2, Long.MAX_VALUE / 2)
    }
}
