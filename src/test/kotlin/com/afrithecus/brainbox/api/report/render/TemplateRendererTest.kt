package com.afrithecus.brainbox.api.report.render

import com.afrithecus.brainbox.api.report.ReportBranding
import com.afrithecus.brainbox.api.report.ReportProperties
import com.afrithecus.brainbox.api.report.ReportTitles
import com.afrithecus.brainbox.api.report.TemplateSpec
import com.afrithecus.brainbox.api.report.web.ReportType
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A11/F22: every report type has a branded v1 template shell with no fabricated data. */
class TemplateRendererTest {

    private val renderer = TemplateRenderer(ReportProperties(embedLogo = false))
    private val branding = ReportBranding(
        schoolName = "Milimani Primary",
        motto = "Knowledge is Power",
        address = "Nairobi",
        watermarkText = "Milimani Primary",
    )

    private fun text(type: ReportType): Pair<Int, String> {
        val bytes = renderer.render(TemplateSpec(ReportTitles.templateTitle(type), branding, type))
        return Loader.loadPDF(bytes).use { document ->
            document.numberOfPages to PDFTextStripper().getText(document)
        }
    }

    @Test
    fun `all nine types render a one-page template shell with the TEMPLATE watermark`() {
        ReportType.entries.forEach { type ->
            val (pages, content) = text(type)
            val compact = content.replace(Regex("\\s+"), "")
            assertEquals(1, pages, "template " + type + " should be one page")
            assertTrue(compact.contains("TEMPLATE"), "watermark missing on " + type)
            assertTrue(compact.contains("MilimaniPrimary"), "branding missing on " + type)
            assertTrue(
                compact.contains(ReportTitles.title(type).replace(" ", "")),
                "title missing on " + type + ": " + compact.take(160),
            )
            assertFalse(compact.contains("73.5"), "template must not print fabricated marks on " + type)
        }
    }

    @Test
    fun `dense list and analysis templates show their real section skeletons`() {
        listOf(ReportType.TRADITIONAL_COMBINED, ReportType.TRADITIONAL_PER_CLASS_TABLES).forEach { type ->
            val (_, content) = text(type)
            val compact = content.replace(Regex("\\s+"), "")
            assertTrue(compact.contains("TOTAL"), "totals skeleton missing on " + type)
            assertTrue(compact.contains("STUDENTSWITHINCOMPLETEMARKS"), "incomplete skeleton missing on " + type)
        }
        val analysis = text(ReportType.TRADITIONAL_GRADE_ANALYSIS).second.replace(Regex("\\s+"), "")
        assertTrue(analysis.contains("CLASSCOMPARISON"), "class comparison skeleton missing")
        assertTrue(analysis.contains("SUBJECT-TEACHERCONTRIBUTION"), "subject-teacher skeleton missing")
    }

    @Test
    fun `detailed templates include their extra sections`() {
        assertTrue(text(ReportType.DETAILED_CBC_CLASS).second.replace(Regex("\\s+"), "").contains("WEAKSTRANDRECOMMENDATIONS"))
        assertTrue(text(ReportType.DETAILED_CBC_STUDENT).second.replace(Regex("\\s+"), "").contains("ENGAGEMENT&FOCUS"))
        assertTrue(text(ReportType.TEACHER_PERFORMANCE).second.replace(Regex("\\s+"), "").contains("STRANDIMPACT"))
    }
}
