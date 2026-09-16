package com.afrithecus.brainbox.api.ops

/**
 * The stable metric-name and dimension vocabulary stored in ops_metric_rollup
 * (O1). Names are part of the contract the future internal ops frontend reads
 * from GET /admin/ops/timeseries, so they must not change silently.
 *
 * Coverage metrics are snapshots, not per-hour counts: they answer "how much of
 * the shelf is published right now", recomputed each run. Everything else is a
 * count/sum/average for the bucket hour. There is deliberately no per-topic
 * dimension here: topic-level coverage lives in GET /admin/ops/coverage.
 */
object OpsMetrics {

    /** Units that resolved to REVIEWED in the bucket (published to the shelf). */
    const val PUBLISHED_UNITS = "content.units.published"

    /** Units auto-approved by the machine in the bucket. */
    const val AUTO_APPROVED = "content.autoapproved"

    /**
     * Auto-approval gate exceptions; dimension is the persisted reason code. This is
     * a distribution snapshot of still-UNREVIEWED units per reason, not a per-hour
     * delta, and it is recomputed from content_units so it is restart-safe.
     */
    const val AUTOAPPROVE_EXCEPTIONS = "content.autoapprove.exceptions"

    /** Generation jobs that ended FAILED in the bucket; dimension is the source. */
    const val JOBS_FAILED = "content.jobs.failed"

    /** Mean provider call latency in milliseconds; dimension is the provider. */
    const val GENERATION_LATENCY_MS = "content.generation.latency_ms"

    /** Failed provider calls; dimension is `<provider>|<reason>`. */
    const val PROVIDER_ERRORS = "content.provider.errors"

    /** Prompt tokens consumed; dimension is the provider. */
    const val TOKENS_PROMPT = "content.tokens.prompt"

    /** Completion tokens consumed; dimension is the provider. */
    const val TOKENS_COMPLETION = "content.tokens.completion"

    /** Cost in micros; dimension is the provider. */
    const val COST_MICROS = "content.cost_micros"

    /** Coverage snapshot: leaf topics with a published notes/quiz unit. */
    const val COVERAGE_PUBLISHED_TOPICS = "content.coverage.published_topics"

    /** Coverage snapshot: all leaf topics on the shelf. */
    const val COVERAGE_TOTAL_TOPICS = "content.coverage.total_topics"

    /** Coverage snapshot: published practice papers. */
    const val COVERAGE_PRACTICE_PAPERS = "content.coverage.published_practice_papers"

    /** Coverage snapshot: published study guides. */
    const val COVERAGE_STUDY_GUIDES = "content.coverage.published_study_guides"

    fun providerErrorDimension(provider: String, reason: String): String = provider + "|" + reason
}
