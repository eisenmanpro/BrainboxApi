package com.afrithecus.brainbox.api.content.entity

import com.afrithecus.brainbox.api.common.jpa.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.util.UUID

/** One execution of the generation pipeline for a job (Phase 7.2). */
@Entity
@Table(name = "agent_runs")
class AgentRunEntity : BaseEntity() {

    @Column(name = "job_id")
    var jobId: UUID? = null

    @Column(name = "generation_key", nullable = false, length = 256)
    var generationKey: String = ""

    @Column(name = "prompt_version", length = 32)
    var promptVersion: String? = null

    @Column(length = 64)
    var model: String? = null

    @Column(nullable = false)
    var iterations: Int = 0

    @Column
    var confidence: Double? = null

    @Column(nullable = false, length = 16)
    var status: String = "RUNNING"
}
