package com.afrithecus.brainbox.api.content

/**
 * The one place the provider token price lives. `ContentRouter` writes
 * `model_calls.cost_micros` from here and the O1 rollup sums those stored micros,
 * so the price is never duplicated as a magic number.
 *
 * DeepSeek public list price, micros per million tokens (v3.2: ~$0.27 in /
 * ~$1.10 out). Stored as micros so cost is integer and currency-agnostic.
 */
object ContentPricing {

    const val PROMPT_MICROS_PER_MILLION = 270_000L
    const val COMPLETION_MICROS_PER_MILLION = 1_100_000L
    private const val MICROS_PER_MILLION = 1_000_000L

    /** The cost of one provider call in micros, rounded down to the whole micro. */
    fun costMicros(promptTokens: Int, completionTokens: Int): Long =
        (promptTokens.toLong() * PROMPT_MICROS_PER_MILLION +
            completionTokens.toLong() * COMPLETION_MICROS_PER_MILLION) / MICROS_PER_MILLION
}
