package com.afrithecus.brainbox.api.content.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.PrePersist
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * One provider call made while executing an [AgentRunEntity] (Phase 7.2).
 * This table has created_at only (no updated_at/version), so it deliberately
 * does not extend BaseEntity.
 */
@Entity
@Table(name = "model_calls")
class ModelCallEntity {

    @Id
    @Column(nullable = false, updatable = false)
    var id: UUID = UUID.randomUUID()

    @Column(name = "agent_run_id", nullable = false)
    var agentRunId: UUID = UUID.randomUUID()

    @Column(nullable = false, length = 32)
    var provider: String = ""

    @Column(length = 64)
    var model: String? = null

    @Column(name = "prompt_tokens", nullable = false)
    var promptTokens: Int = 0

    @Column(name = "completion_tokens", nullable = false)
    var completionTokens: Int = 0

    @Column(name = "cost_micros", nullable = false)
    var costMicros: Long = 0

    @Column(name = "latency_ms", nullable = false)
    var latencyMs: Long = 0

    @Column(nullable = false)
    var success: Boolean = true

    @Column(columnDefinition = "text")
    var error: String? = null

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant = Instant.now()

    @PrePersist
    fun touchOnPersist() {
        createdAt = Instant.now()
    }
}
