package com.afrithecus.brainbox.api.content.subject

import org.springframework.stereotype.Component

/**
 * One domain subject agent: the subject-scoped pedagogy persona appended to the
 * generation system prompt (docs/PHASE7_AGENT_ARCHITECTURE.md 2.3).
 */
data class SubjectAgent(
    val code: String,
    val displayName: String,
    val subjects: Set<String>,
    val persona: String,
)

/**
 * Selects the subject agent for a generation request. Matching is case-insensitive
 * and tolerant of the common aliases in the seeded CBC catalogue (Mathematics/Maths/
 * Math, Integrated Science/Science); an unknown or blank subject falls back to the
 * general agent rather than failing.
 */
@Component
class SubjectAgentRegistry {

    fun forSubject(subject: String?): SubjectAgent {
        val normalized = subject?.trim()?.lowercase().orEmpty()
        if (normalized.isEmpty()) return GENERAL
        return AGENTS.firstOrNull { agent ->
            agent.subjects.any { alias -> normalized == alias || normalized.contains(alias) }
        } ?: GENERAL
    }

    fun all(): List<SubjectAgent> = AGENTS

    private companion object {
        val MATHEMATICS = SubjectAgent(
            code = "MATH",
            displayName = "Mathematics",
            subjects = setOf("mathematics", "maths", "math"),
            persona = "You are an experienced Kenyan CBC mathematics teacher. Work every calculation " +
                "through completely, state the units, and avoid ambiguous notation; every worked example " +
                "must be arithmetically correct.",
        )
        val ENGLISH = SubjectAgent(
            code = "ENG",
            displayName = "English",
            subjects = setOf("english", "literacy"),
            persona = "You are an experienced Kenyan CBC English teacher. Use correct grammar and " +
                "punctuation and age-appropriate vocabulary; keep passages culturally neutral and make every " +
                "comprehension question answerable from the passage.",
        )
        val SCIENCE = SubjectAgent(
            code = "SCI",
            displayName = "Integrated Science",
            subjects = setOf("science", "integrated science", "biology", "chemistry", "physics"),
            persona = "You are an experienced Kenyan CBC integrated science teacher. State scientific facts " +
                "accurately, describe only safe and feasible investigations, and report observations precisely.",
        )
        val KISWAHILI = SubjectAgent(
            code = "KIS",
            displayName = "Kiswahili",
            subjects = setOf("kiswahili", "swahili"),
            persona = "Wewe ni mwalimu mwenye uzoefu wa Kiswahili cha CBC nchini Kenya. Tumia lugha safi ya " +
                "Kiswahili, tahajia na sarufi sahihi, na mifano inayofaa mazingira ya Kenya.",
        )
        val SOCIAL_STUDIES = SubjectAgent(
            code = "SST",
            displayName = "Social Studies",
            subjects = setOf("social studies", "social science", "history", "geography", "citizenship"),
            persona = "You are an experienced Kenyan CBC social studies teacher. Ground examples in the Kenyan " +
                "and East African context, present citizenship and history content fairly and factually, and " +
                "avoid political partisanship.",
        )
        val GENERAL = SubjectAgent(
            code = "GENERAL",
            displayName = "General",
            subjects = emptySet(),
            persona = "You are an experienced Kenyan CBC teacher. Keep the content age-appropriate, accurate " +
                "and grounded in the Kenyan curriculum.",
        )
        val AGENTS = listOf(MATHEMATICS, ENGLISH, SCIENCE, KISWAHILI, SOCIAL_STUDIES)
    }
}
