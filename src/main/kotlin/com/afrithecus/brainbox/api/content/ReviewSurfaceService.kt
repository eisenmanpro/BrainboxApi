package com.afrithecus.brainbox.api.content

import com.afrithecus.brainbox.api.common.error.invalidArgument
import com.afrithecus.brainbox.api.common.error.notFound
import com.afrithecus.brainbox.api.content.entity.ContentFeedbackEntity
import com.afrithecus.brainbox.api.content.entity.ContentReviewEntity
import com.afrithecus.brainbox.api.content.entity.ContentUnitEntity
import com.afrithecus.brainbox.api.content.entity.ModerationOutcomeEntity
import com.afrithecus.brainbox.api.content.repository.ContentFeedbackRepository
import com.afrithecus.brainbox.api.content.repository.ContentUnitRepository
import com.afrithecus.brainbox.api.content.validation.ContentValidationService
import com.afrithecus.brainbox.api.content.validation.ValidationReport
import com.afrithecus.brainbox.api.content.web.ContentFeedbackPayload
import com.afrithecus.brainbox.api.content.web.ContentReviewPayload
import com.afrithecus.brainbox.api.content.web.ModerationOutcomePayload
import com.afrithecus.brainbox.api.content.web.ReviewDecisionRequest
import com.afrithecus.brainbox.api.content.web.ReviewItemPayload
import com.afrithecus.brainbox.api.content.web.ReviewQueueItem
import com.afrithecus.brainbox.api.content.web.ReviewQueuePayload
import com.afrithecus.brainbox.api.content.web.SubmitFeedbackRequest
import com.afrithecus.brainbox.api.content.web.ValidationFindingPayload
import com.afrithecus.brainbox.api.content.web.ValidationReportPayload
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.util.UUID

/**
 * Phase 7.4b teacher review surfaces. Keeps HTTP concerns in the controllers and
 * the workflow in [ReviewService]; this service only assembles the queue, folds the
 * validator report into an item view, and owns the feedback upsert.
 */
@Service
class ReviewSurfaceService(
    private val units: ContentUnitRepository,
    private val reviews: ReviewService,
    private val validation: ContentValidationService,
    private val feedback: ContentFeedbackRepository,
    private val mapper: ObjectMapper,
) {

    @Transactional(readOnly = true)
    fun queue(
        state: String?,
        subject: String?,
        grade: String?,
        limit: Int,
        cursor: String?,
    ): ReviewQueuePayload {
        val capped = limit.coerceIn(1, MAX_LIMIT)
        val after = cursor?.trim()?.takeIf { it.isNotEmpty() }?.let(::parseCursor)
        val rows = units.findForReview(
            state?.trim()?.takeIf { it.isNotEmpty() }?.uppercase(),
            subject?.trim()?.takeIf { it.isNotEmpty() },
            grade?.trim()?.takeIf { it.isNotEmpty() },
            after,
            PageRequest.of(0, capped + 1),
        )
        val hasMore = rows.size > capped
        val items = rows.take(capped).map { it.toQueueItem() }
        return ReviewQueuePayload(
            items = items,
            nextCursor = if (hasMore) items.lastOrNull()?.createdAt?.toString() else null,
        )
    }

    @Transactional(readOnly = true)
    fun item(contentType: String, contentId: UUID, contentVersion: Int): ReviewItemPayload {
        val normalized = requireUnit(contentType)
        val unit = units.findById(contentId).orElseThrow { notFound("content unit not found") }
        return ReviewItemPayload(
            contentType = normalized,
            contentId = unit.id.toString(),
            contentVersion = contentVersion,
            title = unit.title,
            subject = unit.subject,
            grade = unit.gradeLevel,
            conceptId = unit.conceptId?.toString(),
            taskType = unit.taskType,
            provenance = unit.provenance,
            reviewState = unit.reviewState,
            status = unit.status,
            language = unit.language,
            generated = unit.provenance == PROVENANCE_GENERATED,
            validation = validation.validate(normalized, contentId).toPayload(),
            reviews = reviews.listDecisions(normalized, contentId, contentVersion).map { it.toPayload() },
            outcome = reviews.outcome(normalized, contentId, contentVersion)?.toPayload(),
            createdAt = unit.createdAt.toEpochMilli(),
            updatedAt = unit.updatedAt.toEpochMilli(),
        )
    }

    @Transactional(readOnly = true)
    fun decisions(contentType: String, contentId: UUID, contentVersion: Int): List<ContentReviewPayload> {
        val normalized = requireUnit(contentType)
        return reviews.listDecisions(normalized, contentId, contentVersion).map { it.toPayload() }
    }

    @Transactional
    fun decide(
        actor: UserEntity,
        contentType: String,
        contentId: UUID,
        request: ReviewDecisionRequest,
    ): ModerationOutcomePayload {
        val normalized = requireUnit(contentType)
        val outcome = reviews.recordDecision(
            actor = actor,
            contentType = normalized,
            contentId = contentId,
            contentVersion = request.contentVersion ?: DEFAULT_VERSION,
            decision = request.decision,
            reasonTags = request.reasonTags,
            comment = request.comment,
        )
        return outcome.toPayload()
    }

    @Transactional
    fun submitFeedback(actor: UserEntity, request: SubmitFeedbackRequest): ContentFeedbackPayload {
        if (request.score !in MIN_SCORE..MAX_SCORE) {
            throw invalidArgument("feedback score must be between " + MIN_SCORE + " and " + MAX_SCORE)
        }
        val type = request.contentType.trim().uppercase()
        if (type.isEmpty()) throw invalidArgument("content type must not be blank")
        val contentId = parseContentId(request.contentId)

        val entity = feedback.findByReviewerIdAndContentTypeAndContentId(actor.id, type, contentId)
            ?: ContentFeedbackEntity().apply {
                reviewerId = actor.id
                contentType = type
                this.contentId = contentId
            }
        entity.score = request.score
        entity.tags = request.tags.takeIf { it.isNotEmpty() }?.let { mapper.writeValueAsString(it) }
        entity.comment = request.comment
        entity.promptVersion = request.promptVersionId
        entity.provider = request.provider
        entity.model = request.model
        return feedback.saveAndFlush(entity).toPayload()
    }

    @Transactional(readOnly = true)
    fun listFeedback(actor: UserEntity, contentType: String, contentId: UUID): ContentFeedbackPayload? =
        feedback.findByReviewerIdAndContentTypeAndContentId(
            actor.id, contentType.trim().uppercase(), contentId
        )?.toPayload()

    // --------------------------------------------------------------- helpers

    private fun requireUnit(contentType: String): String {
        val normalized = contentType.trim().uppercase()
        if (normalized != ContentValidationService.CONTENT_TYPE_UNIT) {
            throw invalidArgument("unsupported content type: " + contentType)
        }
        return normalized
    }

    private fun parseCursor(cursor: String): Instant =
        cursor.toLongOrNull()?.let { Instant.ofEpochMilli(it) }
            ?: throw invalidArgument("cursor must be an epoch-millis timestamp")

    private fun parseContentId(raw: String): UUID =
        runCatching { UUID.fromString(raw.trim()) }.getOrElse {
            throw invalidArgument("contentId is not a UUID: " + raw)
        }

    private fun ContentUnitEntity.toQueueItem() = ReviewQueueItem(
        contentType = ContentValidationService.CONTENT_TYPE_UNIT,
        contentId = id.toString(),
        title = title,
        subject = subject,
        grade = gradeLevel,
        conceptId = conceptId?.toString(),
        reviewState = reviewState,
        generated = provenance == PROVENANCE_GENERATED,
        createdAt = createdAt.toEpochMilli(),
    )

    private fun ValidationReport.toPayload() = ValidationReportPayload(
        score = score,
        blockers = blockers,
        findings = findings.map {
            ValidationFindingPayload(severity = it.severity.name, code = it.code, message = it.message)
        },
    )

    private fun ContentReviewEntity.toPayload() = ContentReviewPayload(
        reviewerId = reviewerId.toString(),
        decision = decision,
        reasonTags = reasonTags?.let { raw ->
            runCatching { mapper.readValue(raw, Array<String>::class.java).toList() }.getOrNull()
        }.orEmpty(),
        comment = comment,
        weight = weight,
        createdAt = createdAt.toEpochMilli(),
    )

    private fun ModerationOutcomeEntity.toPayload() = ModerationOutcomePayload(
        state = state,
        approvals = approvals,
        rejections = rejections,
        weightedApprovals = weightedApprovals,
        quorumRequired = quorumRequired,
        decidedAt = decidedAt?.toEpochMilli(),
        autoApproved = autoApproved,
        confidenceScore = confidenceScore,
    )

    private fun ContentFeedbackEntity.toPayload() = ContentFeedbackPayload(
        id = id.toString(),
        contentType = contentType,
        contentId = contentId.toString(),
        score = score,
        tags = tags?.let { raw ->
            runCatching { mapper.readValue(raw, Array<String>::class.java).toList() }.getOrNull()
        }.orEmpty(),
        comment = comment,
        promptVersionId = promptVersion,
        provider = provider,
        model = model,
        updatedAt = updatedAt.toEpochMilli(),
    )

    private companion object {
        const val DEFAULT_VERSION = 1
        const val PROVENANCE_GENERATED = "GENERATED"
        const val MAX_LIMIT = 100
        const val MIN_SCORE = 1
        const val MAX_SCORE = 5
    }
}
