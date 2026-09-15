package com.afrithecus.brainbox.api.content.entity

import com.afrithecus.brainbox.api.common.jpa.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/** A queued/running generation job for one concept + task type. */
@Entity
@Table(name = "generation_jobs")
class GenerationJobEntity : BaseEntity() {

    @Column(name = "generation_key", nullable = false, length = 256)
    var generationKey: String = ""

    @Column(name = "task_type", nullable = false, length = 32)
    var taskType: String = ""

    @Column(name = "concept_id")
    var conceptId: UUID? = null

    @Column(name = "grade_level", nullable = false, length = 32)
    var gradeLevel: String = ""

    @Column(nullable = false, length = 16)
    var status: String = "QUEUED"

    /**
     * H2 origin classification (see [com.afrithecus.brainbox.api.content.GenerationJobSource]):
     * USER (interactive), BATCH (Tier 1 producer) or PROACTIVE (future agent).
     * The worker filters and orders on this column.
     */
    @Column(nullable = false, length = 16)
    var source: String = "USER"

    @Column(nullable = false)
    var attempts: Int = 0

    @Column(name = "max_attempts", nullable = false)
    var maxAttempts: Int = 5

    /** The full serialised GenerationRequest, replayed when a worker claims the job. */
    @Column(name = "request_payload", columnDefinition = "text")
    var requestPayload: String? = null

    /** Earliest instant the job may be claimed again; null is immediately claimable. */
    @Column(name = "next_attempt_at")
    var nextAttemptAt: Instant? = null

    @Column(name = "last_error", columnDefinition = "text")
    var lastError: String? = null

    @Column(name = "run_id")
    var runId: UUID? = null
}
