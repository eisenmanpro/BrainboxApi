package com.afrithecus.brainbox.api.content.web

// ---------------------------------------------------------------------------
// Phase 7.4b teacher review-surface payloads: the review queue, one item's
// validation report plus decision history, and the teacher feedback body.
// Readable by the teacher workspace; learners never see UNREVIEWED content.
// ---------------------------------------------------------------------------

data class ReviewQueueItem(
    val contentType: String,
    val contentId: String,
    val title: String?,
    val subject: String,
    val grade: String,
    val conceptId: String?,
    val reviewState: String,
    val generated: Boolean,
    val createdAt: Long,
)

data class ReviewQueuePayload(
    val items: List<ReviewQueueItem>,
    val nextCursor: String? = null,
)

data class ValidationFindingPayload(
    val severity: String,
    val code: String,
    val message: String,
)

data class ValidationReportPayload(
    val score: Double,
    val blockers: Boolean,
    val findings: List<ValidationFindingPayload>,
)

data class ContentReviewPayload(
    val reviewerId: String,
    val decision: String,
    val reasonTags: List<String>,
    val comment: String?,
    val weight: Double,
    val createdAt: Long,
)

data class ModerationOutcomePayload(
    val state: String,
    val approvals: Int,
    val rejections: Int,
    val weightedApprovals: Double,
    val quorumRequired: Int,
    val decidedAt: Long?,
    val autoApproved: Boolean,
    val confidenceScore: Double?,
)

data class ReviewItemPayload(
    val contentType: String,
    val contentId: String,
    val contentVersion: Int,
    val title: String?,
    val subject: String,
    val grade: String,
    val conceptId: String?,
    val taskType: String,
    val provenance: String,
    val reviewState: String,
    val status: String,
    val language: String,
    val generated: Boolean,
    val validation: ValidationReportPayload,
    val reviews: List<ContentReviewPayload>,
    val outcome: ModerationOutcomePayload? = null,
    val createdAt: Long,
    val updatedAt: Long,
)

/** Body of POST /teacher/review/{contentType}/{contentId}/decision. */
data class ReviewDecisionRequest(
    val decision: String,
    val comment: String? = null,
    val reasonTags: List<String> = emptyList(),
    val contentVersion: Int? = null,
)

/** Body of POST /teacher/content/feedback. */
data class SubmitFeedbackRequest(
    val contentType: String,
    val contentId: String,
    val score: Int,
    val tags: List<String> = emptyList(),
    val comment: String? = null,
    val promptVersionId: String? = null,
    val provider: String? = null,
    val model: String? = null,
)

data class ContentFeedbackPayload(
    val id: String,
    val contentType: String,
    val contentId: String,
    val score: Int,
    val tags: List<String>,
    val comment: String?,
    val promptVersionId: String?,
    val provider: String?,
    val model: String?,
    val updatedAt: Long,
)
