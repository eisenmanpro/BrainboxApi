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

    /** H3 cost attribution: the requesting school, or null for platform-scope work. */
    @Column(name = "school_id")
    var schoolId: UUID? = null

    @Column(name = "prompt_version", length = 32)
    var promptVersion: String? = null

    /**
     * The domain subject agent that produced this run (MATH, SCI, ENG, KIS, SST,
     * GENERAL). Part of the capture contract: quality is attributable to an agent, not
     * only to a model.
     */
    @Column(name = "agent_code", length = 32)
    var agentCode: String? = null

    @Column(length = 64)
    var model: String? = null

    @Column(nullable = false)
    var iterations: Int = 0

    @Column
    var confidence: Double? = null

    @Column(nullable = false, length = 16)
    var status: String = "RUNNING"
}
