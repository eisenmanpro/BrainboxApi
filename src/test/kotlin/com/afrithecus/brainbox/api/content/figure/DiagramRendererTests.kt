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
        assertFailsWith<IllegalArgumentException> { DiagramRenderer.render(FigureSpec(kind = "SCATTER")) }
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

    @Test
    fun rendersALineChartWithALegend() {
        val svg = DiagramRenderer.render(
            FigureSpec(
                kind = "LINE",
                title = "Temperature",
                yLabel = "C",
                categories = listOf("Mon", "Tue", "Wed"),
                series = listOf(
                    FigureSeries("Nairobi", listOf(22.0, 24.0, 21.0)),
                    FigureSeries("Kisumu", listOf(26.0, 27.0, 28.0)),
                ),
            ),
        )
        assertTrue(svg.startsWith("<svg "))
        assertTrue(svg.contains(">Nairobi<") && svg.contains(">Kisumu<"), svg)
        assertTrue(svg.contains(">Mon<") && svg.contains(">Wed<"))
        assertEquals(2, count(svg, "<polyline"))
        assertTrue(svg.contains("rotate(-90"))
    }

    @Test
    fun rendersAPieAndCanMakeItADonut() {
        val svg = DiagramRenderer.render(
            FigureSpec(
                kind = "PIE",
                title = "Land use",
                slices = listOf(FigureSlice("Farm", 60.0), FigureSlice("Forest", 40.0)),
            ),
        )
        assertTrue(count(svg, "<path") >= 2)
        assertTrue(svg.contains(">60%<"), svg)
        assertTrue(svg.contains(">Farm<") && svg.contains(">Forest<"))

        val donut = DiagramRenderer.render(FigureSpec(kind = "PIE", slices = listOf(FigureSlice("A", 1.0)), donut = true))
        assertTrue(donut.contains("<circle"), donut)
    }

    @Test
    fun capsPieSlicesAndGroupsTheRest() {
        val slices = (1..12).map { FigureSlice("S" + it, it.toDouble()) }
        val svg = DiagramRenderer.render(FigureSpec(kind = "PIE", slices = slices))
        assertTrue(svg.contains(">Other<"), svg)
        assertTrue(count(svg, "<path") <= 8)
    }

    @Test
    fun rendersANumberLineWithMarksAndIntervals() {
        val svg = DiagramRenderer.render(
            FigureSpec(
                kind = "NUMBER_LINE",
                title = "Inequality",
                min = 0.0,
                max = 10.0,
                step = 1.0,
                marks = listOf(FigureMark(3.0, "3", open = true)),
                intervals = listOf(FigureInterval(6.0, 10.0, "x > 6")),
            ),
        )
        assertTrue(svg.startsWith("<svg "))
        assertTrue(svg.contains("&gt; 6<"), svg)
        assertTrue(svg.contains("<polygon"))
        assertTrue(svg.contains("<rect"))
    }

    @Test
    fun rendersAGeometryFigure() {
        val svg = DiagramRenderer.render(
            FigureSpec(
                kind = "GEOMETRY",
                title = "Triangle",
                viewBox = FigureViewBox(0.0, 0.0, 6.0, 5.0),
                grid = true,
                elements = listOf(
                    FigureElement(
                        type = "POLYGON",
                        points = listOf(listOf(0.0, 0.0), listOf(4.0, 0.0), listOf(2.0, 3.0)),
                        label = "ABC",
                        filled = true,
                    ),
                    FigureElement(type = "CIRCLE", center = listOf(2.0, 1.0), radius = 1.2, label = "O"),
                    FigureElement(type = "SEGMENT", from = listOf(0.0, 0.0), to = listOf(4.0, 0.0), label = "AB"),
                    FigureElement(type = "ANGLE", vertex = listOf(0.0, 0.0), from = listOf(4.0, 0.0), to = listOf(2.0, 3.0), label = "60"),
                ),
            ),
        )
        assertTrue(svg.contains("<polygon"))
        assertTrue(svg.contains("<circle"))
        assertTrue(svg.contains("<path"))
        assertTrue(svg.contains(">ABC<") && svg.contains(">AB<") && svg.contains(">60<"), svg)
        assertTrue(svg.contains("<line"))
    }

    @Test
    fun escapesGeometryLabels() {
        val svg = DiagramRenderer.render(
            FigureSpec(
                kind = "GEOMETRY",
                viewBox = FigureViewBox(0.0, 0.0, 4.0, 4.0),
                elements = listOf(FigureElement(type = "LABEL", at = listOf(1.0, 1.0), text = "<script>x</script>")),
            ),
        )
        assertFalse(svg.contains("<script"), svg)
        assertTrue(svg.contains("&lt;script&gt;"))
    }

    @Test
    fun boundsGeometryElements() {
        val elements = (1..80).map { FigureElement(type = "POINT", at = listOf((it % 5).toDouble(), (it % 4).toDouble())) }
        val svg = DiagramRenderer.render(FigureSpec(kind = "GEOMETRY", viewBox = FigureViewBox(0.0, 0.0, 5.0, 4.0), elements = elements))
        assertEquals(40, count(svg, "<circle"))
    }

    @Test
    fun rendersATree() {
        val svg = DiagramRenderer.render(
            FigureSpec(
                kind = "TREE",
                title = "Vertebrates",
                nodes = listOf(
                    FigureNode("root", "Vertebrates"),
                    FigureNode("fish", "Fish", "root"),
                    FigureNode("birds", "Birds", "root"),
                    FigureNode("mammals", "Mammals", "root"),
                ),
            ),
        )
        assertTrue(svg.startsWith("<svg "))
        assertTrue(svg.contains(">Vertebrates<") && svg.contains(">Fish<") && svg.contains(">Mammals<"), svg)
        assertEquals(4, count(svg, "<rect"))
        assertTrue(svg.contains("<polyline"), svg)
    }

    @Test
    fun rendersAVennDiagram() {
        val svg = DiagramRenderer.render(
            FigureSpec(
                kind = "VENN",
                title = "Living and non-living",
                sets = listOf(FigureSet("Living", listOf("grows", "breathes")), FigureSet("Non-living", listOf("stone"))),
                shared = listOf("water"),
            ),
        )
        assertEquals(2, count(svg, "<circle"))
        assertTrue(svg.contains("fill-opacity"))
        assertTrue(svg.contains(">Living<") && svg.contains(">Non-living<"), svg)
        assertTrue(svg.contains(">grows<") && svg.contains(">stone<") && svg.contains(">water<"), svg)
    }

    @Test
    fun rendersTierTwoGeometryPrimitives() {
        val svg = DiagramRenderer.render(
            FigureSpec(
                kind = "GEOMETRY",
                viewBox = FigureViewBox(0.0, 0.0, 8.0, 6.0),
                elements = listOf(
                    FigureElement(type = "RECT", from = listOf(0.0, 0.0), to = listOf(3.0, 2.0), filled = true),
                    FigureElement(type = "ELLIPSE", center = listOf(5.0, 1.0), radius = 1.5, radiusY = 1.0),
                    FigureElement(type = "ARROW", from = listOf(0.0, 4.0), to = listOf(3.0, 4.0)),
                    FigureElement(
                        type = "BEZIER",
                        from = listOf(3.0, 4.0),
                        to = listOf(6.0, 4.0),
                        control1 = listOf(4.0, 5.5),
                        control2 = listOf(5.0, 2.5),
                    ),
                ),
            ),
        )
        assertTrue(svg.contains("<rect"), svg)
        assertTrue(svg.contains("<ellipse"), svg)
        assertTrue(svg.contains("<polygon"), svg)
        assertTrue(svg.contains("<path"), svg)
    }

    @Test
    fun rendersAnImagePlaceholder() {
        val svg = DiagramRenderer.render(
            FigureSpec(kind = "IMAGE", url = "https://cdn.example.org/cell.png", alt = "A labelled plant cell"),
        )
        assertTrue(svg.startsWith("<svg "))
        assertTrue(svg.contains("A labelled plant cell"), svg)
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
