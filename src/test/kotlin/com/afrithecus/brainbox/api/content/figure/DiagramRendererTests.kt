package com.afrithecus.brainbox.api.content.figure

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The renderer is the security boundary: the model supplies data and labels,
 * the server builds every element. These tests pin escaping, the closed kind
 * vocabulary, list bounds, and determinism.
 */
class DiagramRendererTests {

    @Test
    fun rendersABarChart() {
        val svg = DiagramRenderer.render(
            FigureSpec(
                kind = "BAR",
                title = "Rainfall",
                caption = "Monthly totals",
                xLabel = "Month",
                yLabel = "mm",
                categories = listOf("Jan", "Feb", "Mar"),
                values = listOf(10.0, 40.0, 25.0),
            ),
        )
        assertTrue(svg.startsWith("<svg "), svg.take(60))
        assertTrue(svg.contains("<title>Rainfall</title>"))
        assertTrue(svg.contains(">Jan<") && svg.contains(">Feb<") && svg.contains(">Mar<"))
        assertTrue(svg.contains(">Monthly totals<"))
        assertTrue(svg.contains(">Month<") && svg.contains(">mm<"))
        assertTrue(svg.contains("rotate(-90"))
    }

    @Test
    fun rendersATable() {
        val svg = DiagramRenderer.render(
            FigureSpec(kind = "TABLE", title = "Scores", headers = listOf("Name", "Score"), rows = listOf(listOf("Ann", "90"), listOf("Ben", "80"))),
        )
        assertTrue(svg.contains(">Name<") && svg.contains(">Score<"))
        assertTrue(svg.contains(">Ann<") && svg.contains(">Ben<") && svg.contains(">90<"))
    }

    @Test
    fun rendersAFlowAndCanLoopIt() {
        val straight = DiagramRenderer.render(FigureSpec(kind = "FLOW", steps = listOf("Collect", "Measure", "Record")))
        assertTrue(straight.contains(">Collect<") && straight.contains(">Record<"))
        assertTrue(straight.contains("<polygon"))

        val cyclic = DiagramRenderer.render(FigureSpec(kind = "FLOW", steps = listOf("A", "B", "C"), cyclic = true))
        assertTrue(count(cyclic, "<polygon") > count(straight, "<polygon"))
        assertTrue(cyclic.contains(">A<"))
    }

    @Test
    fun escapesModelSuppliedText() {
        val svg = DiagramRenderer.render(
            FigureSpec(
                kind = "BAR",
                title = "<script>alert(1)</script>",
                categories = listOf("A & B", "<img src=x onerror=alert(1)>"),
                values = listOf(1.0, 2.0),
            ),
        )
        assertFalse(svg.contains("<script"), svg)
        assertFalse(svg.contains("<img"), svg)
        assertTrue(svg.contains("&lt;script&gt;"))
        assertTrue(svg.contains("A &amp; B"))
        assertFalse(svg.contains("onerror=alert"), svg)
    }

    @Test
    fun escapesTableCellText() {
        val svg = DiagramRenderer.render(
            FigureSpec(kind = "TABLE", headers = listOf("A") , rows = listOf(listOf("</td><script>x</script>"))),
        )
        assertFalse(svg.contains("<script"), svg)
        assertTrue(svg.contains("&lt;/td&gt;"))
    }

    @Test
    fun boundsTheNumberOfElements() {
        val bar = DiagramRenderer.render(
            FigureSpec(kind = "BAR", categories = (1..40).map { "c" + it }, values = (1..40).map { it.toDouble() }),
        )
        assertEquals(12, count(bar, "<rect"))

        val table = DiagramRenderer.render(
            FigureSpec(kind = "TABLE", headers = (1..10).map { "h" + it }, rows = (1..30).map { listOf("v") }),
        )
        assertEquals(21, count(table, "<rect"))

        val flow = DiagramRenderer.render(FigureSpec(kind = "FLOW", steps = (1..12).map { "s" + it }))
        assertEquals(8, count(flow, "<rect"))
    }

    @Test
    fun rejectsUnknownKind() {
        assertFailsWith<IllegalArgumentException> { DiagramRenderer.render(FigureSpec(kind = "PIE")) }
    }

    @Test
    fun isDeterministic() {
        val spec = FigureSpec(kind = "BAR", categories = listOf("A", "B"), values = listOf(1.0, 3.0))
        assertEquals(DiagramRenderer.render(spec), DiagramRenderer.render(spec))
    }

    @Test
    fun survivesDegenerateInput() {
        val bar = DiagramRenderer.render(FigureSpec(kind = "BAR", values = listOf(Double.NaN, Double.POSITIVE_INFINITY)))
        assertTrue(bar.startsWith("<svg "))
        val empty = DiagramRenderer.render(FigureSpec(kind = "FLOW"))
        assertTrue(empty.startsWith("<svg "))
    }

    private fun count(haystack: String, needle: String): Int {
        var index = haystack.indexOf(needle)
        var total = 0
        while (index >= 0) {
            total++
            index = haystack.indexOf(needle, index + needle.length)
        }
        return total
    }
}
