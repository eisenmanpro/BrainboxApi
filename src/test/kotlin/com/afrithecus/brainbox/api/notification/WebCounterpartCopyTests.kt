package com.afrithecus.brainbox.api.notification

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * product_ops_roadmap item 6: the notice must never point a teacher at a guessed address.
 * An unconfigured web app says "ask your administrator" instead of inventing a domain.
 */
class WebCounterpartCopyTests {

    @Test
    fun `a configured address is offered`() {
        val body = WebCounterpartCopy.body("https://learn.brainbox.africa")
        assertTrue(body.contains("https://learn.brainbox.africa"))
        assertFalse(body.lowercase().contains("ask your school administrator"))
    }

    @Test
    fun `an unconfigured address is never invented`() {
        listOf(null, "", "   ").forEach { blank ->
            val body = WebCounterpartCopy.body(blank)
            assertTrue(body.lowercase().contains("ask your school administrator")) { "blank url must ask, not guess" }
            assertFalse(body.contains("http")) { "no address may be printed when none is configured" }
        }
    }
}
