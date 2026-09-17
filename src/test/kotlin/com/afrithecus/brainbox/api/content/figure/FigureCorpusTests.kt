package com.afrithecus.brainbox.api.content.figure

import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The fidelity and safety lock for the figure vocabulary. Every kind in
 * [FigureSpecs.KINDS] must have a sample here, validate, render to deterministic
 * SVG, and never emit an active or external-content element. If a kind is added
 * without updating this corpus the first test fails, which is the point.
 */
class FigureCorpusTests {

    private val mapper = JsonMapper.builder().build()

    @Test
    fun everyKindValidatesRendersDeterministicallyAndSafely() {
        assertEquals(FigureSpecs.KINDS, SAMPLES.keys, "every kind needs a corpus sample")
        SAMPLES.forEach { (kind, json) ->
            val node = mapper.readTree(json)
            val violations = FigureSpecs.validate(node)
            assertTrue(violations.isEmpty(), kind + " sample must validate: " + violations)
            val svg = DiagramRenderer.render(FigureSpecs.parse(node))
            assertTrue(svg.startsWith("<svg "), kind)
            assertTrue(svg.contains("<title>"), kind)
            assertSafe(svg, kind)
            assertEquals(svg, DiagramRenderer.render(FigureSpecs.parse(node)), kind + " must render deterministically")
        }
    }

    @Test
    fun escapesHostileLabelsInEveryKind() {
        HOSTILE.forEach { json ->
            val svg = DiagramRenderer.render(FigureSpecs.parse(mapper.readTree(json)))
            assertFalse(svg.contains("<script", ignoreCase = true), json)
            assertSafe(svg, json)
        }
    }

    private fun assertSafe(svg: String, label: String) {
        listOf("<script", "onload=", "onerror=", "javascript:", "<foreignobject", "<iframe", "<!entity", "<image").forEach { marker ->
            assertFalse(svg.contains(marker, ignoreCase = true), label + " emitted " + marker + ": " + svg)
        }
    }

    private companion object {
        val SAMPLES: Map<String, String> = mapOf(
            "TABLE" to
                "{\"kind\":\"TABLE\",\"title\":\"Marks\",\"caption\":\"Term one\"," +
                "\"headers\":[\"Name\",\"Score\"],\"rows\":[[\"Ann\",\"90\"],[\"Ben\",\"80\"]]}",
            "BAR" to
                "{\"kind\":\"BAR\",\"title\":\"Rainfall\",\"xLabel\":\"Month\",\"yLabel\":\"mm\"," +
                "\"categories\":[\"Jan\",\"Feb\",\"Mar\"],\"values\":[10,40,25],\"showValues\":true}",
            "LINE" to
                "{\"kind\":\"LINE\",\"title\":\"Temperature\",\"yLabel\":\"C\",\"categories\":[\"Mon\",\"Tue\",\"Wed\"]," +
                "\"series\":[{\"name\":\"Nairobi\",\"values\":[22,24,21]},{\"name\":\"Kisumu\",\"values\":[26,27,28]}]}",
            "PIE" to
                "{\"kind\":\"PIE\",\"title\":\"Land use\",\"slices\":[{\"label\":\"Farm\",\"value\":60},{\"label\":\"Forest\",\"value\":40}]}",
            "NUMBER_LINE" to
                "{\"kind\":\"NUMBER_LINE\",\"title\":\"Inequality\",\"min\":0,\"max\":10,\"step\":1," +
                "\"marks\":[{\"value\":3,\"label\":\"3\",\"open\":true}],\"intervals\":[{\"from\":6,\"to\":10,\"label\":\"x > 6\"}]}",
            "FLOW" to
                "{\"kind\":\"FLOW\",\"title\":\"Water cycle\",\"steps\":[\"Evaporate\",\"Condense\",\"Precipitate\",\"Collect\"],\"cyclic\":true}",
            "GEOMETRY" to
                "{\"kind\":\"GEOMETRY\",\"title\":\"Triangle\",\"viewBox\":{\"minX\":0,\"minY\":0,\"width\":6,\"height\":5},\"grid\":true," +
                "\"elements\":[" +
                "{\"type\":\"POLYGON\",\"points\":[[0,0],[4,0],[2,3]],\"label\":\"ABC\",\"filled\":true}," +
                "{\"type\":\"CIRCLE\",\"center\":[2,1],\"radius\":1.2,\"label\":\"O\"}," +
                "{\"type\":\"ARROW\",\"from\":[0,4],\"to\":[3,4],\"label\":\"d\"}," +
                "{\"type\":\"BEZIER\",\"from\":[3,4],\"control1\":[4,5],\"control2\":[5,3],\"to\":[6,4]}," +
                "{\"type\":\"RECT\",\"from\":[4,0],\"to\":[6,2],\"filled\":true}," +
                "{\"type\":\"ELLIPSE\",\"center\":[1,3],\"radius\":1,\"radiusY\":0.5}]}",
            "TREE" to
                "{\"kind\":\"TREE\",\"title\":\"Vertebrates\",\"nodes\":[{\"id\":\"r\",\"label\":\"Vertebrates\"}," +
                "{\"id\":\"f\",\"label\":\"Fish\",\"parent\":\"r\"},{\"id\":\"b\",\"label\":\"Birds\",\"parent\":\"r\"}]}",
            "VENN" to
                "{\"kind\":\"VENN\",\"title\":\"Living and non-living\",\"sets\":[{\"label\":\"Living\",\"items\":[\"grows\"]}," +
                "{\"label\":\"Non-living\",\"items\":[\"stone\"]}],\"shared\":[\"water\"]}",
            "IMAGE" to
                "{\"kind\":\"IMAGE\",\"title\":\"Plant cell\",\"url\":\"https://cdn.example.org/cell.png\"," +
                "\"alt\":\"A labelled plant cell\",\"caption\":\"Figure 1\"}",
        )

        val HOSTILE: List<String> = listOf(
            "{\"kind\":\"TABLE\",\"headers\":[\"H\"],\"rows\":[[\"<script>alert(1)</script>\"]]}",
            "{\"kind\":\"BAR\",\"categories\":[\"<script>\"],\"values\":[1]}",
            "{\"kind\":\"LINE\",\"categories\":[\"<script>\"],\"series\":[{\"name\":\"<script>\",\"values\":[1]}]}",
            "{\"kind\":\"PIE\",\"slices\":[{\"label\":\"<script>\",\"value\":1}]}",
            "{\"kind\":\"NUMBER_LINE\",\"min\":0,\"max\":1,\"marks\":[{\"value\":1,\"label\":\"<script>\"}]}",
            "{\"kind\":\"FLOW\",\"steps\":[\"<script>alert(1)</script>\"]}",
            "{\"kind\":\"GEOMETRY\",\"viewBox\":{\"width\":4,\"height\":4},\"elements\":[{\"type\":\"LABEL\",\"at\":[1,1],\"text\":\"<script>\"}]}",
            "{\"kind\":\"TREE\",\"nodes\":[{\"id\":\"a\",\"label\":\"<script>\"}]}",
            "{\"kind\":\"VENN\",\"sets\":[{\"label\":\"<script>\"},{\"label\":\"B\"}],\"shared\":[\"<script>\"]}",
            "{\"kind\":\"IMAGE\",\"url\":\"https://cdn.example.org/a.png\",\"alt\":\"<script>\",\"caption\":\"<script>\"}",
        )
    }
}
