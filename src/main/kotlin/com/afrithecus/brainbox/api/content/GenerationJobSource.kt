package com.afrithecus.brainbox.api.content

import com.afrithecus.brainbox.api.common.error.invalidArgument

/**
 * H2 source classification for generation work. Every durable job records where
 * the request originated so the worker can make autonomous generation a runtime
 * policy decision rather than an all-or-nothing drain:
 *
 * - [USER]: a teacher/student request through the submit endpoint or
 *   [ContentRouter.resolve]. Interactive work.
 * - [BATCH]: the 7.5e Tier 1 producer ([com.afrithecus.brainbox.api.content.batch.ContentBatchService]),
 *   i.e. notes/learning material generated ahead of demand.
 * - [PROACTIVE]: reserved for the future autonomous agent that pre-generates
 *   content without a user request.
 *
 * Claim priority is USER first, then BATCH, then PROACTIVE (see
 * [com.afrithecus.brainbox.api.content.repository.GenerationJobRepository.findClaimable]),
 * so a user request is never stuck behind a seed batch.
 */
object GenerationJobSource {

    const val USER = "USER"
    const val BATCH = "BATCH"
    const val PROACTIVE = "PROACTIVE"

    /** Every source the queue can carry, in claim-priority order. */
    val ALL: List<String> = listOf(USER, BATCH, PROACTIVE)

    fun isKnown(value: String): Boolean = value.trim().uppercase() in ALL

    /** Lower is claimed first: USER (0), BATCH (1), PROACTIVE/unknown (2). */
    fun priority(source: String): Int = when (source.trim().uppercase()) {
        USER -> 0
        BATCH -> 1
        else -> 2
    }

    /**
     * Normalises and validates an internal source argument. Callers pass the
     * constants above; an unknown value is a programming error surfaced as an
     * invalid argument rather than a row that violates the DB CHECK.
     */
    fun normalize(source: String): String {
        val normalized = source.trim().uppercase()
        if (!isKnown(normalized)) {
            throw invalidArgument(
                "unknown generation job source: " + source + "; supported: " + ALL.joinToString(", "),
            )
        }
        return normalized
    }
}
