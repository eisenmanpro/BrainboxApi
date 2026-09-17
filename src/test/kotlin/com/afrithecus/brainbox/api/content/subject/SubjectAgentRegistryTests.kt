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
}
