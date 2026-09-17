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
        val violation = firstBlocker(read("{\"kind\":\"SCATTER\",\"categories\":[\"A\"],\"values\":[1]}"))
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

    @Test
    fun acceptsTheExtendedKinds() {
        assertTrue(
            FigureSpecs.validate(read("{\"kind\":\"LINE\",\"categories\":[\"A\",\"B\"],\"series\":[{\"name\":\"S\",\"values\":[1,2]}]}")).isEmpty(),
        )
        assertTrue(FigureSpecs.validate(read("{\"kind\":\"PIE\",\"slices\":[{\"label\":\"A\",\"value\":1}]}")).isEmpty())
        assertTrue(FigureSpecs.validate(read("{\"kind\":\"NUMBER_LINE\",\"min\":0,\"max\":10,\"step\":1}")).isEmpty())
        assertTrue(
            FigureSpecs.validate(
                read("{\"kind\":\"GEOMETRY\",\"viewBox\":{\"width\":4,\"height\":3},\"elements\":[{\"type\":\"SEGMENT\",\"from\":[0,0],\"to\":[4,0]}]}"),
            ).isEmpty(),
        )
    }

    @Test
    fun enforcesLineShape() {
        assertEquals("FIGURE_SERIES_MISSING", firstBlocker(read("{\"kind\":\"LINE\",\"categories\":[\"A\"]}")).code)
        assertEquals(
            "FIGURE_LENGTH_MISMATCH",
            firstBlocker(read("{\"kind\":\"LINE\",\"categories\":[\"A\",\"B\"],\"series\":[{\"values\":[1]}]}")).code,
        )
    }

    @Test
    fun enforcesPieShape() {
        assertEquals("FIGURE_SLICES_MISSING", firstBlocker(read("{\"kind\":\"PIE\"}")).code)
        assertEquals(
            "FIGURE_SLICE_VALUE_INVALID",
            firstBlocker(read("{\"kind\":\"PIE\",\"slices\":[{\"label\":\"A\",\"value\":-1}]}")).code,
        )
        assertEquals(
            "FIGURE_SLICES_EMPTY_TOTAL",
            firstBlocker(read("{\"kind\":\"PIE\",\"slices\":[{\"label\":\"A\",\"value\":0}]}")).code,
        )
    }

    @Test
    fun enforcesNumberLineShape() {
        assertEquals("FIGURE_RANGE_MISSING", firstBlocker(read("{\"kind\":\"NUMBER_LINE\"}")).code)
        assertEquals("FIGURE_RANGE_INVALID", firstBlocker(read("{\"kind\":\"NUMBER_LINE\",\"min\":5,\"max\":5}")).code)
    }

    @Test
    fun enforcesGeometryShape() {
        assertEquals(
            "FIGURE_VIEWBOX_MISSING",
            firstBlocker(read("{\"kind\":\"GEOMETRY\",\"elements\":[{\"type\":\"POINT\",\"at\":[0,0]}]}")).code,
        )
        assertEquals(
            "FIGURE_ELEMENTS_MISSING",
            firstBlocker(read("{\"kind\":\"GEOMETRY\",\"viewBox\":{\"width\":4,\"height\":3}}")).code,
        )
        assertEquals(
            "FIGURE_ELEMENT_TYPE_INVALID",
            firstBlocker(
                read("{\"kind\":\"GEOMETRY\",\"viewBox\":{\"width\":4,\"height\":3},\"elements\":[{\"type\":\"SPIRAL\",\"at\":[0,0]}]}"),
            ).code,
        )
        assertEquals(
            "FIGURE_ELEMENT_INVALID",
            firstBlocker(
                read("{\"kind\":\"GEOMETRY\",\"viewBox\":{\"width\":4,\"height\":3},\"elements\":[{\"type\":\"SEGMENT\",\"from\":[0,0]}]}"),
            ).code,
        )
        assertEquals(
            "FIGURE_ELEMENT_INVALID",
            firstBlocker(
                read(
                    "{\"kind\":\"GEOMETRY\",\"viewBox\":{\"width\":4,\"height\":3},\"elements\":[{\"type\":\"BEZIER\",\"from\":[0,0],\"to\":[1,1]}]}",
                ),
            ).code,
        )
    }

    @Test
    fun acceptsTreeVennAndTierTwoPrimitives() {
        assertTrue(
            FigureSpecs.validate(
                read("{\"kind\":\"TREE\",\"nodes\":[{\"id\":\"a\",\"label\":\"A\"},{\"id\":\"b\",\"label\":\"B\",\"parent\":\"a\"}]}"),
            ).isEmpty(),
        )
        assertTrue(
            FigureSpecs.validate(read("{\"kind\":\"VENN\",\"sets\":[{\"label\":\"A\"},{\"label\":\"B\"}],\"shared\":[\"x\"]}")).isEmpty(),
        )
        assertTrue(
            FigureSpecs.validate(
                read(
                    "{\"kind\":\"GEOMETRY\",\"viewBox\":{\"width\":4,\"height\":4},\"elements\":[" +
                        "{\"type\":\"BEZIER\",\"from\":[0,0],\"to\":[1,1],\"control1\":[0,1],\"control2\":[1,0]}," +
                        "{\"type\":\"ELLIPSE\",\"center\":[2,2],\"radius\":1,\"radiusY\":0.5}]}",
                ),
            ).isEmpty(),
        )
    }

    @Test
    fun enforcesTreeShape() {
        assertEquals("FIGURE_NODES_MISSING", firstBlocker(read("{\"kind\":\"TREE\"}")).code)
        assertEquals(
            "FIGURE_TREE_NO_ROOT",
            firstBlocker(
                read("{\"kind\":\"TREE\",\"nodes\":[{\"id\":\"a\",\"label\":\"A\",\"parent\":\"b\"},{\"id\":\"b\",\"label\":\"B\",\"parent\":\"a\"}]}"),
            ).code,
        )
        assertEquals(
            "FIGURE_PARENT_UNKNOWN",
            firstBlocker(read("{\"kind\":\"TREE\",\"nodes\":[{\"id\":\"a\",\"label\":\"A\"},{\"id\":\"b\",\"label\":\"B\",\"parent\":\"zzz\"}]}")).code,
        )
    }

    @Test
    fun enforcesVennShape() {
        assertEquals("FIGURE_SETS_MISSING", firstBlocker(read("{\"kind\":\"VENN\"}")).code)
        assertEquals("FIGURE_SETS_INVALID", firstBlocker(read("{\"kind\":\"VENN\",\"sets\":[{\"label\":\"A\"}]}")).code)
    }

    private fun read(json: String) = mapper.readTree(json)

    private fun firstBlocker(node: tools.jackson.databind.JsonNode) =
        FigureSpecs.validate(node).first { it.severity == FindingSeverity.BLOCKER }
}
