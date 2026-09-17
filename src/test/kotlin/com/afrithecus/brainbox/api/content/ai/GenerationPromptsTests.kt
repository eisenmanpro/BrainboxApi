package com.afrithecus.brainbox.api.content.ai

import kotlin.test.Test
import kotlin.test.assertTrue

class GenerationPromptsTests {

    @Test
    fun appendsThePersonaAfterTheBaseSchema() {
        val base = GenerationPrompts.systemPrompt(null)
        assertTrue(base.contains("\"correctAnswer\""), base.take(80))
        val withPersona = GenerationPrompts.systemPrompt("You are a test persona.")
        assertTrue(withPersona.startsWith(base))
        assertTrue(withPersona.endsWith("You are a test persona."))
        // A blank persona never adds whitespace noise.
        assertTrue(GenerationPrompts.systemPrompt("  ") == base)
    }
}
