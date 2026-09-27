package com.afrithecus.brainbox.api.console

import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant
import java.util.UUID

interface ConsoleNotificationRepository : JpaRepository<ConsoleNotificationEntity, UUID> {

    /** Notifications and direct messages share the table; each history reads only its channel. */
    fun findAllByChannelOrderByCreatedAtDesc(channel: String, pageable: Pageable): List<ConsoleNotificationEntity>

    fun findAllByChannelAndStatusOrderByCreatedAtDesc(
        channel: String,
        status: String,
        pageable: Pageable,
    ): List<ConsoleNotificationEntity>

    /** Messages the scheduler should send: still scheduled and due. */
    fun findAllByStatusAndScheduledAtLessThanEqual(status: String, due: Instant): List<ConsoleNotificationEntity>

    fun countByStatus(status: String): Long
}

interface ConsoleAutomationRuleRepository : JpaRepository<ConsoleAutomationRuleEntity, UUID> {

    fun findAllByOrderByCreatedAtDesc(): List<ConsoleAutomationRuleEntity>

    fun findAllByEnabledTrueAndTriggerType(triggerType: String): List<ConsoleAutomationRuleEntity>

    fun findByNameIgnoreCase(name: String): ConsoleAutomationRuleEntity?
}

interface SubjectAgentPromptRepository : JpaRepository<SubjectAgentPromptEntity, UUID> {

    fun findByAgentCode(agentCode: String): SubjectAgentPromptEntity?

    fun findAllByOrderByAgentCodeAsc(): List<SubjectAgentPromptEntity>
}
