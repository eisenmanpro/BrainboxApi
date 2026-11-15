package com.afrithecus.brainbox.api.report.render

import com.afrithecus.brainbox.api.identity.web.SchoolConfigPayload
import com.afrithecus.brainbox.api.report.ReportBranding
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.text.PDFTextStripper
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * D16/A23: the report stamp is the school's uploaded image when configured, and the standard
 * double-ruled Exam Office stamp otherwise. The image is supplied as an inline data URL so the
 * test needs no network and exercises the same fetch path production uses.
 */
class SchoolStampRendererTest {

    private fun pngDataUrl(): String {
        val image = BufferedImage(64, 32, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        graphics.color = Color(0x1F, 0x2A, 0x44)
        graphics.fillRoundRect(2, 2, 60, 28, 8, 8)
        graphics.dispose()
        val out = ByteArrayOutputStream()
        ImageIO.write(image, "png", out)
        return "data:image/png;base64," + Base64.getEncoder().encodeToString(out.toByteArray())
    }

    private fun renderStamp(branding: ReportBranding): Pair<Boolean, String> {
        PDDocument().use { document ->
            val theme = ReportTheme(document)
            val canvas = ReportCanvas(document, theme, PDRectangle.A4.width, PDRectangle.A4.height)
            ReportFurniture.horizontalStamp(canvas, branding, 36f, 700f)
            canvas.close()
            val out = ByteArrayOutputStream()
            document.save(out)
            val bytes = out.toByteArray()
            Loader.loadPDF(bytes).use { reloaded ->
                val text = PDFTextStripper().getText(reloaded)
                val hasImage = reloaded.getPage(0).resources.xObjectNames.any { true }
                return hasImage to text
            }
        }
    }

    @Test
    fun `uploaded school stamp replaces the exam office stamp`() {
        val (hasImage, text) = renderStamp(ReportBranding("Milimani Primary School", stampUrl = pngDataUrl()))
        assertTrue(hasImage, "the school stamp image should be embedded in the PDF")
        assertFalse(text.filterNot { it.isWhitespace() }.contains("EXAMOFFICE"), "the standard stamp must not also be drawn: " + text)
    }

    @Test
    fun `unset stamp falls back to the standard exam office stamp`() {
        val (_, text) = renderStamp(ReportBranding("Milimani Primary School"))
        // Tracked caps extract with one space per glyph, so compare the flattened label.
        val compact = text.filterNot { it.isWhitespace() }
        assertTrue(compact.contains("EXAMOFFICE"), "the standard stamp must be drawn when no school stamp is configured")
        assertTrue(text.contains("Official record"))
    }

    @Test
    fun `branding payload carries the school stamp and logo for report rendering`() {
        val branding = ReportBranding.from(
            SchoolConfigPayload(
                schoolId = "school-1",
                schoolName = "Milimani Primary School",
                logoUrl = "https://cdn.example/logo.png",
                stampUrl = "https://cdn.example/stamp.png",
            ),
        )
        assertEquals("https://cdn.example/logo.png", branding.logoUrl)
        assertEquals("https://cdn.example/stamp.png", branding.stampUrl)
        assertNotNull(branding.schoolName)
    }
}
