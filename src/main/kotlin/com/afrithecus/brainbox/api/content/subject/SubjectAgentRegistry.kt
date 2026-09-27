package com.afrithecus.brainbox.api.content.subject

import com.afrithecus.brainbox.api.content.ContentTaskTypes
import org.springframework.stereotype.Component

/**
 * A console edit of an agent's prompt text. Content asks for this through
 * [SubjectAgentPromptLookup] so the registry stays independent of the console package.
 */
data class AgentPromptOverride(
    val persona: String,
    val assessmentGuidance: String,
    val notesGuidance: String,
    val version: Int,
)

/** Where a console's prompt overrides come from; null when the console has never edited one. */
fun interface SubjectAgentPromptLookup {
    fun overrideFor(agentCode: String): AgentPromptOverride?
}

/**
 * One domain subject agent: the subject-scoped pedagogy persona appended to the
 * generation system prompt (docs/PHASE7_AGENT_ARCHITECTURE.md 2.3).
 *
 * The BrainBox standard means each agent is *concept + subject + pedagogy*, so the
 * persona is only half of it: [assessmentGuidance] and [notesGuidance] carry the
 * task-shaped half, because an assessment and a study guide from the same subject need
 * different instructions (question craft and unambiguous keys versus explained steps and
 * worked examples).
 */
data class SubjectAgent(
    val code: String,
    val displayName: String,
    val subjects: Set<String>,
    val persona: String,
    /** Added for assessment task types (QUIZ, EXAM, ASSESSMENT, PRACTICE_PAPER). */
    val assessmentGuidance: String = "",
    /** Added for readable/lesson task types (NOTES, BOOK, CHUNK, LESSON, STUDY_GUIDE). */
    val notesGuidance: String = "",
)

/**
 * The subject agent plus the prompt block it contributes for one task type, so callers
 * get both the attribution ([code]) and the text in one resolution.
 */
data class SubjectAgentAssignment(
    val agent: SubjectAgent,
    val personaBlock: String,
) {
    val code: String get() = agent.code
}

/**
 * Selects the subject agent for a generation request and builds its prompt block.
 *
 * Matching is deliberate rather than fuzzy: an exact alias wins, otherwise an alias that
 * appears as a whole word wins, and the **longest** match decides between candidates. A
 * plain "contains" test made "Social Science" resolve to Integrated Science, because
 * "science" is a substring of it; a domain agent teaching the wrong subject is worse than
 * the general fallback.
 */
@Component
class SubjectAgentRegistry(
    /**
     * The console's prompt overrides, when it has any. Optional so the registry can be built
     * without the console (unit tests) and so a deployment that never edits a prompt is
     * unaffected.
     */
    private val overrides: SubjectAgentPromptLookup? = null,
) {

    fun forSubject(subject: String?): SubjectAgent = withOverride(agentFor(subject))

    /** The agent for [subject] plus its prompt block for [taskType]. */
    fun assignmentFor(subject: String?, taskType: String?): SubjectAgentAssignment {
        // Null taskType keeps the pure persona: the caller is not generating content yet.
        if (taskType == null) {
            val agent = withOverride(agentFor(subject))
            return SubjectAgentAssignment(agent = agent, personaBlock = agent.persona)
        }
        val agent = withOverride(agentFor(subject))
        val guidance = if (ContentTaskTypes.isAssessment(taskType)) agent.assessmentGuidance else agent.notesGuidance
        val block = if (guidance.isBlank()) agent.persona else agent.persona + "\n\n" + guidance
        return SubjectAgentAssignment(agent = agent, personaBlock = block)
    }

    /**
     * Applies the console's override, if one exists, to the agent's prompt text. The roster —
     * which agents exist and what they match — always comes from code.
     */
    private fun withOverride(agent: SubjectAgent): SubjectAgent {
        val override = overrides?.overrideFor(agent.code) ?: return agent
        return agent.copy(
            persona = override.persona.ifBlank { agent.persona },
            assessmentGuidance = override.assessmentGuidance.ifBlank { agent.assessmentGuidance },
            notesGuidance = override.notesGuidance.ifBlank { agent.notesGuidance },
        )
    }

    /** The domain agents only (the fallback is not a domain). */
    fun all(): List<SubjectAgent> = AGENTS

    /**
     * Every agent a generation can be attributed to: the domain agents plus the general
     * fallback, which is where a request with no matching subject lands.
     */
    fun roster(): List<SubjectAgent> = AGENTS + GENERAL

    /** The general agent's code; a request with no domain agent is attributed to it. */
    val generalCode: String get() = GENERAL.code

    private fun agentFor(subject: String?): SubjectAgent {
        val normalized = subject?.trim()?.lowercase()?.replace(Regex("\\s+"), " ") ?: ""
        if (normalized.isEmpty()) return GENERAL
        // Exact alias first, so a short alias cannot be beaten by a longer unrelated one.
        AGENTS.forEach { agent ->
            if (agent.subjects.any { it == normalized }) return agent
        }
        // Then a whole-word alias, longest first, so "social science" (an SST alias)
        // resolves to SST rather than to the "science" alias of Integrated Science.
        return AGENTS
            .flatMap { agent -> agent.subjects.map { alias -> agent to alias } }
            .filter { (_, alias) -> isWholeWord(normalized, alias) }
            .maxByOrNull { (_, alias) -> alias.length }
            ?.first
            ?: GENERAL
    }

    /** True when [alias] appears in [subject] bounded by non-letters on both sides. */
    private fun isWholeWord(subject: String, alias: String): Boolean =
        Regex("(?<![a-z])" + Regex.escape(alias) + "(?![a-z])").containsMatchIn(subject)

    private companion object {
        val MATHEMATICS = SubjectAgent(
            code = "MATH",
            displayName = "Mathematics",
            subjects = setOf("mathematics", "maths", "math"),
            persona = "You are an experienced Kenyan CBC mathematics teacher. Work every calculation " +
                "through completely, state the units, and avoid ambiguous notation; every worked example " +
                "must be arithmetically correct.",
            assessmentGuidance = "For mathematics questions, make each stem answerable without guessing: " +
                "state the method the learner must use, give exactly one unambiguous correct answer, and " +
                "make the marking points explicit in the explanation.",
            notesGuidance = "For mathematics lessons, show the method before the result and include at " +
                "least one fully worked example per step with its working, not just the answer.",
        )
        val ENGLISH = SubjectAgent(
            code = "ENG",
            displayName = "English",
            subjects = setOf("english", "literacy"),
            persona = "You are an experienced Kenyan CBC English teacher. Use correct grammar and " +
                "punctuation and age-appropriate vocabulary; keep passages culturally neutral and make every " +
                "comprehension question answerable from the passage.",
            assessmentGuidance = "For English questions, every comprehension question must be answerable " +
                "from the passage alone, and grammar keys must accept exactly one form as correct.",
            notesGuidance = "For English lessons, model the language feature in a short passage, then name " +
                "the rule, then have the learner apply it.",
        )
        val SCIENCE = SubjectAgent(
            code = "SCI",
            displayName = "Integrated Science",
            subjects = setOf("science", "integrated science", "biology", "chemistry", "physics"),
            persona = "You are an experienced Kenyan CBC integrated science teacher. State scientific facts " +
                "accurately, describe only safe and feasible investigations, and report observations precisely.",
            assessmentGuidance = "For science questions, use only safe, locally available materials, and " +
                "accept an answer the learner could reach from the stated observation rather than from " +
                "outside knowledge.",
            notesGuidance = "For science lessons, follow observe -> explain -> apply, and describe every " +
                "investigation with the materials a Kenyan classroom actually has.",
        )
        val KISWAHILI = SubjectAgent(
            code = "KIS",
            displayName = "Kiswahili",
            subjects = setOf("kiswahili", "swahili"),
            persona = "Wewe ni mwalimu mwenye uzoefu wa Kiswahili cha CBC nchini Kenya. Tumia lugha safi ya " +
                "Kiswahili, tahajia na sarufi sahihi, na mifano inayofaa mazingira ya Kenya.",
            assessmentGuidance = "Kwa maswali ya Kiswahili, hakikisha jibu moja tu linawezekana na " +
                "linaungwa mkono na kifungu kilichotolewa.",
            notesGuidance = "Kwa masomo ya Kiswahili, anza kwa mfano, eleza kanuni, kisha mpe mwanafunzi " +
                "nafasi ya kuitumia.",
        )
        val SOCIAL_STUDIES = SubjectAgent(
            code = "SST",
            displayName = "Social Studies",
            subjects = setOf("social studies", "social science", "history", "geography", "citizenship"),
            persona = "You are an experienced Kenyan CBC social studies teacher. Ground examples in the Kenyan " +
                "and East African context, present citizenship and history content fairly and factually, and " +
                "avoid political partisanship.",
            assessmentGuidance = "For social studies questions, keep every key a matter of verifiable fact " +
                "or of the source given, and never make a question turn on a contested political reading.",
            notesGuidance = "For social studies lessons, anchor each concept in a Kenyan or East African " +
                "example before generalising.",
        )
        val GENERAL = SubjectAgent(
            code = "GENERAL",
            displayName = "General",
            subjects = emptySet(),
            persona = "You are an experienced Kenyan CBC teacher. Keep the content age-appropriate, accurate " +
                "and grounded in the Kenyan curriculum.",
            assessmentGuidance = "Make each question answerable from the material given, with one " +
                "unambiguous correct answer and a stated marking point.",
            notesGuidance = "Break the topic into explained steps with an example the learner can follow.",
        )
        val AGENTS = listOf(MATHEMATICS, ENGLISH, SCIENCE, KISWAHILI, SOCIAL_STUDIES)
    }
}
