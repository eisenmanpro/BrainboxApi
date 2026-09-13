package com.afrithecus.brainbox.api.content.entity

import com.afrithecus.brainbox.api.common.jpa.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
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

    @Column(name = "review_state", nullable = false, length = 16)
    var reviewState: String = "UNREVIEWED"

    @Column(nullable = false, length = 16)
    var status: String = "DRAFT"

    @Column(name = "published_at")
    var publishedAt: Instant? = null
}
