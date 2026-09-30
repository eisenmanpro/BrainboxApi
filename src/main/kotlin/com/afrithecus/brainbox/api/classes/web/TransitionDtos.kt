package com.afrithecus.brainbox.api.classes.web

/**
 * Learner search + grade/stream transition contract
 * (docs/ongoing/product_ops_roadmap.md item 3). Field names are the clients' wire names.
 */
data class TransitionCandidatePayload(
    val studentId: String,
    val name: String,
    val admissionNumber: String? = null,
    val grade: String? = null,
    val stream: String? = null,
    val classId: String? = null,
    val className: String? = null,
)

data class TransitionRequest(
    val toClassId: String,
    val reason: String? = null,
    /** PULL, PUSH or PROMOTE; defaults to PUSH. */
    val mode: String? = null,
)

data class TransitionResultPayload(
    val studentId: String,
    val fromClassId: String? = null,
    val toClassId: String,
    val removedFromClassIds: List<String> = emptyList(),
    val mode: String,
    val message: String,
)

/** Coordinator on/off switch for grade transitioning (product_ops_roadmap item 4). */
data class TransitionSettingsPayload(val enabled: Boolean)

data class TransitionHistoryPayload(
    val id: String,
    val studentId: String,
    val fromClassId: String?,
    val toClassId: String,
    val mode: String,
    val reason: String?,
    val initiatedBy: String,
    val createdAt: Long,
)
