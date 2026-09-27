package com.afrithecus.brainbox.api.console

import com.afrithecus.brainbox.api.common.error.invalidArgument
import com.afrithecus.brainbox.api.common.error.notFound
import com.afrithecus.brainbox.api.content.subject.SubjectAgent
import com.afrithecus.brainbox.api.content.subject.SubjectAgentRegistry
import com.afrithecus.brainbox.api.console.web.SubjectAgentPromptRequest
import com.afrithecus.brainbox.api.console.web.SubjectAgentPromptView
import com.afrithecus.brainbox.api.identity.AuditLogService
import com.afrithecus.brainbox.api.identity.PlatformAccessService
import com.afrithecus.brainbox.api.identity.PlatformPermission
import com.afrithecus.brainbox.api.identity.model.CurrentUser
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * The console's edit of the per-subject agent prompts.
 *
 * The registry in code is the **default** and stays authoritative for the roster (which agents
 * exist, which subjects each covers and the matching rules); this service stores an override of
 * one agent's text. `SubjectAgentRegistry` reads the override when building a prompt, so an edit
 * here changes generation without a release — and because every write bumps
 * `promptVersion`, the capture tables can say which text produced a unit.
 */
@Service
class SubjectAgentPromptService(
    private val prompts: SubjectAgentPromptRepository,
    private val registry: SubjectAgentRegistry,
    private val access: PlatformAccessService,
    private val audit: AuditLogService,
) : com.afrithecus.brainbox.api.content.subject.SubjectAgentPromptLookup {

    /**
     * The registry's read path: the stored override for one agent, or null for the code
     * default. Deliberately not permission-gated — it runs inside content generation, not a
     * console request.
     */
    @Transactional(readOnly = true)
    override fun overrideFor(agentCode: String): com.afrithecus.brainbox.api.content.subject.AgentPromptOverride? =
        prompts.findByAgentCode(agentCode)?.let { row ->
            com.afrithecus.brainbox.api.content.subject.AgentPromptOverride(
                persona = row.persona,
                assessmentGuidance = row.assessmentGuidance,
                notesGuidance = row.notesGuidance,
                version = row.promptVersion,
            )
        }

    @Transactional(readOnly = true)
    fun list(current: CurrentUser): List<SubjectAgentPromptView> {
        access.requirePermission(current, PlatformPermission.CONTENT_AGENTS_MANAGE)
        val overrides = prompts.findAllByOrderByAgentCodeAsc().associateBy { it.agentCode }
        val rows = registry.all().map { agent -> view(agent, overrides[agent.code]) }
        // A stored override for an agent that no longer exists is surfaced rather than hidden:
        // it is inert, and an operator should be able to delete it.
        val orphans = overrides.filterKeys { code -> registry.all().none { it.code == code } }
        return rows + orphans.values.map { row ->
            SubjectAgentPromptView(
                agentCode = row.agentCode,
                displayName = row.agentCode + " (retired)",
                subjects = emptyList(),
                persona = row.persona,
                assessmentGuidance = row.assessmentGuidance,
                notesGuidance = row.notesGuidance,
                overridden = true,
                promptVersion = row.promptVersion,
                updatedAt = row.updatedAt.toEpochMilli(),
            )
        }
    }

    @Transactional
    fun update(
        current: CurrentUser,
        agentCodeRaw: String,
        request: SubjectAgentPromptRequest,
    ): SubjectAgentPromptView {
        val actor = access.requirePermission(current, PlatformPermission.CONTENT_AGENTS_MANAGE)
        val code = agentCodeRaw.trim().uppercase()
        val agent = registry.all().firstOrNull { it.code.equals(code, ignoreCase = true) }
            ?: throw notFound("Unknown subject agent '$agentCodeRaw'")
        if (request.persona.isBlank()) throw invalidArgument("A persona is required")
        if (request.persona.length > 4000) throw invalidArgument("A persona must be 4000 characters or fewer")
        if (request.assessmentGuidance.length > 2000 || request.notesGuidance.length > 2000) {
            throw invalidArgument("Guidance must be 2000 characters or fewer")
        }
        // A first override starts at version 1; an edit of an existing one bumps it.
        val row = prompts.findByAgentCode(agent.code) ?: SubjectAgentPromptEntity().apply {
            this.agentCode = agent.code
            promptVersion = 0
        }
        row.persona = request.persona.trim()
        row.assessmentGuidance = request.assessmentGuidance.trim()
        row.notesGuidance = request.notesGuidance.trim()
        row.promptVersion += 1
        row.updatedBy = actor.id
        prompts.save(row)
        audit.recordPlatform(
            actor = actor,
            action = "subject_agent_prompt_updated",
            target = "agent:" + agent.code,
            detail = agent.displayName + " prompt version " + row.promptVersion,
        )
        return view(agent, row)
    }

    /** Removes the override, so the agent falls back to the seeded default. */
    @Transactional
    fun reset(current: CurrentUser, agentCodeRaw: String): SubjectAgentPromptView {
        val actor = access.requirePermission(current, PlatformPermission.CONTENT_AGENTS_MANAGE)
        val code = agentCodeRaw.trim().uppercase()
        val agent = registry.all().firstOrNull { it.code.equals(code, ignoreCase = true) }
            ?: throw notFound("Unknown subject agent '$agentCodeRaw'")
        prompts.findByAgentCode(agent.code)?.let { prompts.delete(it) }
        audit.recordPlatform(
            actor = actor,
            action = "subject_agent_prompt_reset",
            target = "agent:" + agent.code,
            detail = agent.displayName + " reverted to the seeded default",
        )
        return view(agent, null)
    }

    private fun view(agent: SubjectAgent, override: SubjectAgentPromptEntity?) = SubjectAgentPromptView(
        agentCode = agent.code,
        displayName = agent.displayName,
        subjects = agent.subjects.sorted(),
        persona = override?.persona ?: agent.persona,
        assessmentGuidance = override?.assessmentGuidance ?: agent.assessmentGuidance,
        notesGuidance = override?.notesGuidance ?: agent.notesGuidance,
        overridden = override != null,
        promptVersion = override?.promptVersion ?: 0,
        updatedAt = override?.updatedAt?.toEpochMilli(),
    )
}
