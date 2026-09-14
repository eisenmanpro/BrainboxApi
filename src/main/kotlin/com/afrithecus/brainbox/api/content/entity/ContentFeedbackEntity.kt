package com.afrithecus.brainbox.api.content.entity

import com.afrithecus.brainbox.api.common.jpa.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.util.UUID

/**
 * A teacher rating of a content item (Phase 7.4a). This is a separate signal from
 * the review decision: it carries the 1-5 score plus the generation identity
 * (prompt version, model, run id) so the preference dataset can be attributed.
 */
@Entity
@Table(name = "content_feedback")
class ContentFeedbackEntity : BaseEntity() {

    @Column(name = "content_type", nullable = false, length = 16)
    var contentType: String = ""

    @Column(name = "content_id", nullable = false)
    var contentId: UUID = UUID.randomUUID()

    @Column(name = "reviewer_id", nullable = false)
    var reviewerId: UUID = UUID.randomUUID()

    @Column(nullable = false)
    var score: Int = 3

    @Column(columnDefinition = "text")
    var tags: String? = null

    @Column(columnDefinition = "text")
    var comment: String? = null

    @Column(name = "prompt_version", length = 32)
    var promptVersion: String? = null

    @Column(length = 64)
    var model: String? = null

    @Column(name = "agent_run_id")
    var agentRunId: UUID? = null
}
