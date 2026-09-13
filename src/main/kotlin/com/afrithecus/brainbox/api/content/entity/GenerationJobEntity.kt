package com.afrithecus.brainbox.api.content.entity

import com.afrithecus.brainbox.api.common.jpa.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
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

    @Column(nullable = false)
    var attempts: Int = 0

    @Column(name = "last_error", columnDefinition = "text")
    var lastError: String? = null

    @Column(name = "run_id")
    var runId: UUID? = null
}
