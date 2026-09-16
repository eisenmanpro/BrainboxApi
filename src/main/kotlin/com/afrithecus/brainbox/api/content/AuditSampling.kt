package com.afrithecus.brainbox.api.content

import java.util.UUID

/**
 * O1 sampled audit of machine approvals. A stable hash of the content id decides
 * the sample flag, so the same unit is always in or out of the sample regardless
 * of when or how often the auto-approval path runs (never random per call).
 *
 * The bucket space is 10,000 and one percent is 100 buckets, so the chosen
 * percentage is honoured to two decimal places. 0 disables sampling; 100 samples
 * everything.
 */
object AuditSampling {

    /** Effective default when `auto_approve_audit_sample_percent` is absent. */
    const val DEFAULT_PERCENT = 2.0

    private const val BUCKETS = 10_000L
    private const val BUCKETS_PER_PERCENT = 100.0

    /** True when [contentId] falls in the deterministic [percent] sample. */
    fun isSampled(contentId: UUID, percent: Double): Boolean {
        if (percent <= 0.0) return false
        if (percent >= 100.0) return true
        val mixed = contentId.mostSignificantBits xor contentId.leastSignificantBits
        val bucket = Math.floorMod(mixed, BUCKETS)
        val threshold = Math.round(percent * BUCKETS_PER_PERCENT)
        return bucket < threshold
    }
}
