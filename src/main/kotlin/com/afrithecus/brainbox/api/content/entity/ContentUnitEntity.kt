package com.afrithecus.brainbox.api.content.entity

import com.afrithecus.brainbox.api.common.jpa.BaseEntity
import com.afrithecus.brainbox.api.learning.model.LearningScope
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * A cached, generated or uploaded content unit keyed by its deterministic
 * generation key (Phase 7.1). Steps and questions live in child tables.
 */
@Entity
@Table(name = "content_units")
class ContentUnitEntity : BaseEntity() {

    @Column(name = "generation_key", nullable = false, length = 256)
    var generationKey: String = ""

    @Column(name = "task_type", nullable = false, length = 32)
    var taskType: String = ""

    @Column(length = 255)
    var title: String? = null

    @Column(name = "concept_id")
    var conceptId: UUID? = null

    @Column(nullable = false, length = 64)
    var subject: String = ""

    @Column(name = "grade_level", nullable = false, length = 32)
    var gradeLevel: String = ""

    @Column(nullable = false, length = 8)
    var language: String = "en"

    @Column(name = "standard_version", nullable = false, length = 16)
    var standardVersion: String = "v1"

    @Column(name = "schema_version", nullable = false, length = 16)
    var schemaVersion: String = "v1"

    @Column(name = "prompt_version", length = 32)
    var promptVersion: String? = null

    @Column(columnDefinition = "text")
    var body: String? = null

    @Column(nullable = false, length = 16)
    var provenance: String = "GENERATED"

    @Column(name = "author_name", length = 160)
    var authorName: String? = null

    @Column(name = "source_urls", columnDefinition = "text")
    var sourceUrls: String? = null

    @Column(length = 64)
    var license: String? = null

    @Column(length = 64)
    var model: String? = null

    @Column(nullable = false)
    var tokens: Int = 0

    @Column
    var confidence: Double? = null

    /** Phase 7.5f: independent answer-key agreement ratio (agreements / questions). */
    @Column(name = "answer_key_agreement")
    var answerKeyAgreement: Double? = null

    /** Phase 7.5f: when the independent verification last ran; null means unverified. */
    @Column(name = "answer_key_verified_at")
    var answerKeyVerifiedAt: Instant? = null

    /** Phase 7.5f: model that returned the independent answers. */
    @Column(name = "answer_key_verified_model", length = 64)
    var answerKeyVerifiedModel: String? = null

    /** Phase 7.5h: number of disputed questions dropped after independent verification. */
    @Column(name = "answer_key_dropped", nullable = false)
    var answerKeyDropped: Int = 0

    /** Phase 7.5h: JSON audit array of the dropped items ({orderIndex, text, storedKey, verifiedAnswer}). */
    @Column(name = "answer_key_dropped_detail", columnDefinition = "text")
    var answerKeyDroppedDetail: String? = null

    /**
     * O1: the stable reason code for the most recent machine refusal, cleared to null
     * when the unit auto-approves. A human decision does not need to clear it because
     * the ops reason query is scoped to UNREVIEWED units.
     */
    @Column(name = "auto_approve_blocked_reason", length = 64)
    var autoApproveBlockedReason: String? = null

    /**
     * Phase 7.5 scope inheritance: GLOBAL reaches every learner; SCHOOL is limited
     * to [schoolId] (and the unit's grade); SCHOOL_GRADE_CLASS is the most specific.
     * The projection copies these onto the client-facing row.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    var scope: LearningScope = LearningScope.GLOBAL

    @Column(name = "school_id")
    var schoolId: UUID? = null

    /** Phase 7.5 LLM critic score for the current content, 0..1; null = not critiqued. */
    @Column(name = "critique_score")
    var critiqueScore: Double? = null

    @Column(name = "critique_at")
    var critiqueAt: Instant? = null

    @Column(name = "critique_model", length = 64)
    var critiqueModel: String? = null

    /** JSON array of the critic's structured findings. */
    @Column(name = "critique_findings", columnDefinition = "text")
    var critiqueFindings: String? = null

    @Column(name = "review_state", nullable = false, length = 16)
    var reviewState: String = "UNREVIEWED"

    @Column(nullable = false, length = 16)
    var status: String = "DRAFT"

    @Column(name = "published_at")
    var publishedAt: Instant? = null
}
