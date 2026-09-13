package com.afrithecus.brainbox.api.content.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.PrePersist
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * One MCP tool invocation made while executing an [AgentRunEntity] (Phase 7.2).
 * This table has created_at only, so it does not extend BaseEntity.
 */
@Entity
@Table(name = "tool_calls")
class ToolCallEntity {

    @Id
    @Column(nullable = false, updatable = false)
    var id: UUID = UUID.randomUUID()

    @Column(name = "agent_run_id", nullable = false)
    var agentRunId: UUID = UUID.randomUUID()

    @Column(name = "tool_name", nullable = false, length = 64)
    var toolName: String = ""

    @Column(nullable = false)
    var success: Boolean = true

    @Column(name = "latency_ms", nullable = false)
    var latencyMs: Long = 0

    @Column(name = "request_json", columnDefinition = "text")
    var requestJson: String? = null

    @Column(name = "response_json", columnDefinition = "text")
    var responseJson: String? = null

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant = Instant.now()

    @PrePersist
    fun touchOnPersist() {
        createdAt = Instant.now()
    }
}
