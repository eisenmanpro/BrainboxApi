package com.afrithecus.brainbox.api.content.figure

import com.afrithecus.brainbox.api.content.validation.FindingSeverity
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The closed figure vocabulary: each kind has required fields, a structural
 * violation is a BLOCKER (so a bad spec never reaches the renderer), and unknown
 * kinds are rejected rather than guessed at.
 */
class FigureSpecsTests {

    private val mapper = JsonMapper.builder().build()

    @Test
    fun acceptsWellFormedSpecs() {
        assertTrue(FigureSpecs.validate(read("{\"kind\":\"BAR\",\"categories\":[\"A\"],\"values\":[1]}")).isEmpty())
        assertTrue(FigureSpecs.validate(read("{\"kind\":\"TABLE\",\"headers\":[\"H\"],\"rows\":[[\"a\"]]}")).isEmpty())
        assertTrue(FigureSpecs.validate(read("{\"kind\":\"FLOW\",\"steps\":[\"one\",\"two\"]}")).isEmpty())
    }

    @Test
    fun rejectsNonObjectAndMissingKind() {
        assertEquals("FIGURE_NOT_OBJECT", firstBlocker(read("[1,2]")).code)
        assertEquals("FIGURE_KIND_MISSING", firstBlocker(read("{\"title\":\"x\"}")).code)
    }

    @Test
    fun rejectsUnknownKind() {
        val violation = firstBlocker(read("{\"kind\":\"PIE\",\"categories\":[\"A\"],\"values\":[1]}"))
        assertEquals("FIGURE_KIND_INVALID", violation.code)
        assertEquals(FindingSeverity.BLOCKER, violation.severity)
    }

    @Test
    fun enforcesBarShape() {
        assertEquals("FIGURE_CATEGORIES_MISSING", firstBlocker(read("{\"kind\":\"BAR\",\"values\":[1]}")).code)
        assertEquals("FIGURE_VALUES_MISSING", firstBlocker(read("{\"kind\":\"BAR\",\"categories\":[\"A\"]}")).code)
        assertEquals(
            "FIGURE_LENGTH_MISMATCH",
            firstBlocker(read("{\"kind\":\"BAR\",\"categories\":[\"A\",\"B\"],\"values\":[1]}")).code,
        )
    }

    @Test
    fun enforcesTableShape() {
        assertEquals("FIGURE_HEADERS_MISSING", firstBlocker(read("{\"kind\":\"TABLE\",\"rows\":[[\"a\"]]}")).code)
        assertEquals("FIGURE_ROWS_INVALID", firstBlocker(read("{\"kind\":\"TABLE\",\"headers\":[\"H\"],\"rows\":[1]}")).code)
        assertEquals("FIGURE_ROWS_INVALID", firstBlocker(read("{\"kind\":\"TABLE\",\"headers\":[\"H\"],\"rows\":[[1]]}")).code)
    }

    @Test
    fun enforcesFlowShape() {
        assertEquals("FIGURE_STEPS_MISSING", firstBlocker(read("{\"kind\":\"FLOW\"}")).code)
        assertEquals("FIGURE_STEPS_MISSING", firstBlocker(read("{\"kind\":\"FLOW\",\"steps\":[]}")).code)
    }

    @Test
    fun kindIsCaseInsensitiveAndTrimmed() {
        assertTrue(FigureSpecs.validate(read("{\"kind\":\" bar \"}")).isEmpty() == false)
        assertTrue(firstBlocker(read("{\"kind\":\" bar \"}")).code == "FIGURE_CATEGORIES_MISSING")
    }

    private fun read(json: String) = mapper.readTree(json)

    private fun firstBlocker(node: tools.jackson.databind.JsonNode) =
        FigureSpecs.validate(node).first { it.severity == FindingSeverity.BLOCKER }
}
