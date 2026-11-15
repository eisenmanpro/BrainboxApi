package com.afrithecus.brainbox.api.report.render

import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.text.PDFTextStripper
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReportCanvasTest {

    @Test
    fun `adaptive layout matches the approved geometry matrix`() {
        val s5 = ReportTableLayout(5)
        assertFalse(s5.landscape)
        assertEquals(595.28f, s5.pageWidth, 0.01f)
        assertEquals(841.89f, s5.pageHeight, 0.01f)
        assertEquals(41, s5.rowsPerPage)
        assertEquals(53.5f, s5.pairW, 0.1f)

        val s9 = ReportTableLayout(9)
        assertTrue(s9.landscape)
        assertEquals(31, s9.rowsPerPage)
        assertEquals(57.1f, s9.pairW, 0.1f)

        val s13 = ReportTableLayout(13)
        assertTrue(s13.compact)
        assertEquals(35, s13.rowsPerPage)
        assertEquals(41.8f, s13.pairW, 0.1f)

        val s17 = ReportTableLayout(17)
        assertTrue(s17.extreme)
        assertEquals(35, s17.rowsPerPage)
        assertEquals(34.5f, s17.pairW, 0.1f)
    }

    @Test
    fun `page plan fills capacity and only the last page carries totals`() {
        val layout = ReportTableLayout(13)
        val pages = layout.plan(500)
        assertEquals(layout.rowsPerPage, pages.first().rows)
        assertEquals(500, pages.sumOf { it.rows })
        assertTrue(pages.last().totals)
        assertEquals(1, pages.count { it.totals })
    }

    @Test
    fun `extraction keeps ti pairs and accented glyphs (A14, F20)`() {
        PDDocument().use { document ->
            val theme = ReportTheme(document)
            val canvas = ReportCanvas(document, theme, PDRectangle.A4.width, PDRectangle.A4.height)
            canvas.text("Otieno correction continued Mathematics Ngũgĩ", 36f, 700f, theme.sans, 10f, ReportTheme.INK)
            canvas.close()
            val bytes = ByteArrayOutputStream().also { document.save(it) }.toByteArray()
            Loader.loadPDF(bytes).use { reloaded ->
                val text = PDFTextStripper().getText(reloaded)
                listOf("Otieno", "correction", "continued", "Mathematics", "Ngũgĩ").forEach { word ->
                    assertTrue(text.contains(word), "missing \"" + word + "\" in: " + text)
                }
            }
        }
    }

    @Test
    fun `canvas renders and text extracts including accented names`() {
        PDDocument().use { document ->
            val theme = ReportTheme(document)
            val canvas = ReportCanvas(document, theme, PDRectangle.A4.width, PDRectangle.A4.height)
            canvas.text("Milimani Primary School", 36f, 780f, theme.serifBold, 16f, ReportTheme.NAVY)
            canvas.tracked("ACADEMIC YEAR 2026", 36f, 760f, theme.sansBold, 6f, 1.2f, ReportTheme.TEAL)
            canvas.text("Ngũgĩ wa Thiong'o - Mathematics", 36f, 700f, theme.sans, 9f, ReportTheme.INK)
            canvas.close()
            val bytes = ByteArrayOutputStream().also { document.save(it) }.toByteArray()
            Loader.loadPDF(bytes).use { reloaded ->
                val text = PDFTextStripper().getText(reloaded)
                assertTrue(text.contains("Milimani Primary School"), text)
                assertTrue(text.contains("Ngũgĩ"), text)
            }
        }
    }
}
