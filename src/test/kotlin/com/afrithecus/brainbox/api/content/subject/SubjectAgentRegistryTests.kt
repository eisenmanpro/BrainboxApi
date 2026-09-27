package com.afrithecus.brainbox.api.content.subject

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SubjectAgentRegistryTests {

    private val registry = SubjectAgentRegistry()

    @Test
    fun mapsTheSeededSubjectsAndAliases() {
        assertEquals("MATH", registry.forSubject("Mathematics").code)
        assertEquals("MATH", registry.forSubject("Maths").code)
        assertEquals("MATH", registry.forSubject("math").code)
        assertEquals("ENG", registry.forSubject("English").code)
        assertEquals("SCI", registry.forSubject("Integrated Science").code)
        assertEquals("SCI", registry.forSubject("Science").code)
        assertEquals("KIS", registry.forSubject("Kiswahili").code)
        assertEquals("SST", registry.forSubject("Social Studies").code)
    }

    @Test
    fun fallsBackToTheGeneralAgentWithANonBlankPersona() {
        assertEquals("GENERAL", registry.forSubject(null).code)
        assertEquals("GENERAL", registry.forSubject("").code)
        assertEquals("GENERAL", registry.forSubject("Creative Arts").code)
        assertTrue(registry.all().all { it.persona.isNotBlank() })
    }

    @Test
    fun aWholeWordAliasBeatsASubstringOfAnotherAgent() {
        // "Social Science" contains "science"; a substring match sent it to Integrated
        // Science, which is the wrong domain. The longest whole-word alias must win.
        assertEquals("SST", registry.forSubject("Social Science").code)
        assertEquals("SST", registry.forSubject("social science").code)
        assertEquals("SST", registry.forSubject("Social Studies").code)
        // ... and the genuine science subjects still resolve to science.
        assertEquals("SCI", registry.forSubject("Integrated Science").code)
        assertEquals("SCI", registry.forSubject("Physics").code)
        assertEquals("SCI", registry.forSubject("Physical Science").code)
    }

    @Test
    fun whitespaceAndCaseDoNotChangeTheAgent() {
        assertEquals("MATH", registry.forSubject("  MATHEMATICS  ").code)
        assertEquals("SST", registry.forSubject("Social   Studies").code)
    }

    @Test
    fun thePromptBlockCarriesTheTaskShapedPedagogy() {
        val assessment = registry.assignmentFor("Mathematics", "PRACTICE_PAPER")
        assertEquals("MATH", assessment.code)
        assertTrue(
            assessment.personaBlock.contains("exactly one unambiguous correct answer"),
            assessment.personaBlock,
        )

        val notes = registry.assignmentFor("Mathematics", "STUDY_GUIDE")
        assertTrue(notes.personaBlock.contains("fully worked example"), notes.personaBlock)

        // Both blocks keep the subject persona itself.
        assertTrue(assessment.personaBlock.startsWith(registry.forSubject("Mathematics").persona))
        assertTrue(notes.personaBlock.startsWith(registry.forSubject("Mathematics").persona))

        // An unknown task type takes the lesson guidance rather than none, and a null
        // task type is just the persona.
        assertEquals(
            registry.forSubject("Mathematics").persona,
            registry.assignmentFor("Mathematics", null).personaBlock,
        )
    }

    @Test
    fun everyAgentCarriesGuidanceForBothTaskShapes() {
        for (agent in registry.all()) {
            assertTrue(agent.assessmentGuidance.isNotBlank(), agent.code + " needs assessment guidance")
            assertTrue(agent.notesGuidance.isNotBlank(), agent.code + " needs lesson guidance")
            assertTrue(agent.displayName.isNotBlank(), agent.code + " needs a display name")
        }
        // The fallback agent is not in the list; every listed agent is addressable.
        assertTrue(registry.all().none { it.code == registry.generalCode })
    }
}
