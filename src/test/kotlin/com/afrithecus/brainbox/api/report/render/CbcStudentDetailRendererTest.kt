package com.afrithecus.brainbox.api.report.render

import com.afrithecus.brainbox.api.cbcratings.web.CbcReportCardPayload
import com.afrithecus.brainbox.api.cbcratings.web.CbcStrandRatingPayload
import com.afrithecus.brainbox.api.cbcratings.web.PaperExamResultPayload
import com.afrithecus.brainbox.api.report.CbcStudentCard
import com.afrithecus.brainbox.api.report.CbcStudentDetail
import com.afrithecus.brainbox.api.report.CbcStudentsSpec
import com.afrithecus.brainbox.api.report.ReportBranding
import com.afrithecus.brainbox.api.report.ReportProperties
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A7/Q9/F7: DETAILED_CBC_STUDENT renders two pages per learner - the unchanged A4 card and
 * the analytics page - with page numbers spanning the document and no repeat of the
 * learning-area table. Learners/parents get no detail analytics, so that page must show an
 * explicit unavailable state instead of invented figures.
 */
class CbcStudentDetailRendererTest {

    private val branding = ReportBranding(
        schoolName = "Riverside Academy",
        motto = "Nurture - Discover - Excel",
        address = "P.O. Box 2145, Nairobi",
        phone = "+254 700 112 233",
        email = "info@riverside.ac.ke",
        watermarkText = "Riverside Academy",
    )

    private val properties = ReportProperties(embedLogo = false)
    private val renderer = CbcStudentRenderer(properties)

    private fun ratings(): List<CbcStrandRatingPayload> = listOf(
        CbcStrandRatingPayload("CBC1", "Expresses ideas clearly and listens to others", "EXCEEDING", strandName = "Communication & Collaboration"),
        CbcStrandRatingPayload("CBC2", "Applies reasoning to unfamiliar tasks", "MEETING", strandName = "Critical Thinking & Problem Solving"),
        CbcStrandRatingPayload("CBC3", "Explores ideas and makes connections", "EXCEEDING", strandName = "Imagination & Creativity"),
        CbcStrandRatingPayload("CBC4", "Shows responsibility and care for the community", "MEETING", strandName = "Citizenship"),
        CbcStrandRatingPayload("CBC5", "Reflects on progress and sets next steps", "MEETING", strandName = "Learning to Learn"),
        CbcStrandRatingPayload("CBC6", "Works independently and asks for help", "MEETING", strandName = "Self-Efficacy"),
        CbcStrandRatingPayload("CBC7", "Uses digital tools purposefully and safely", "APPROACHING", strandName = "Digital Literacy"),
    )

    private fun papers(): List<PaperExamResultPayload> = listOf(
        PaperExamResultPayload(
            subject = "Mathematics",
            score = 74.0,
            maxScore = 100.0,
            percentage = 74.0,
            grade = "ME",
            examTitle = "End Term 3 2026",
            term = "Term 3",
            year = 2026,
            examDate = "12 November 2026",
        ),
        PaperExamResultPayload("English", 81.0, 100.0, 81.0, "EE", examTitle = "End Term 3 2026", term = "Term 3", year = 2026, examDate = "12 November 2026"),
        PaperExamResultPayload("Science & Technology", 68.0, 100.0, 68.0, "ME", examTitle = "End Term 3 2026", term = "Term 3", year = 2026, examDate = "12 November 2026"),
    )

    private fun card(
        detail: CbcStudentDetail?,
        strandRatings: List<CbcStrandRatingPayload> = ratings(),
    ): CbcStudentCard = CbcStudentCard(
        studentId = "s_1",
        studentName = "Amina Wanjiku Mwangi",
        gradeLevel = "Grade 4 East",
        term = "TERM_3",
        year = 2026,
        detail = detail,
        card = CbcReportCardPayload(
            studentName = "Amina Wanjiku Mwangi",
            term = "TERM_3",
            strandRatings = strandRatings,
            teacherComments = "Amina reads widely and contributes confidently in class discussions.",
            attendancePercentage = 94.2,
            overallGrade = "ME",
            paperExamResults = papers(),
            gradeLevel = "Grade 4 East",
            admissionNumber = "RVA-4021",
        ),
    )

    private fun detail(percentile: Int? = 80): CbcStudentDetail = CbcStudentDetail(
        learningStreakDays = 12,
        totalXp = 1840,
        classAverageScore = 78.5,
        classPercentile = percentile,
    )

    private fun text(bytes: ByteArray, page: Int? = null): String = Loader.loadPDF(bytes).use { document ->
        PDFTextStripper().apply {
            if (page != null) {
                startPage = page
                endPage = page
            }
        }.getText(document)
    }

    @Test
    fun `detailed cbc student adds the analytics page to the card`() {
        val bytes = renderer.render(CbcStudentsSpec("Detailed Student CBC Report", branding, listOf(card(detail())), detailed = true))
        Loader.loadPDF(bytes).use { document ->
            assertEquals(2, document.numberOfPages, "a detailed card must render its two pages")
        }
        val cardPage = text(bytes, page = 1)
        assertTrue(cardPage.contains("Amina Wanjiku Mwangi"), "page 1 must stay the learner card")
        assertTrue(cardPage.contains("Digital Literacy"), "page 1 keeps the learning-area table")
        assertTrue(cardPage.replace(Regex("\\s+"), "").contains("PAPEREXAMRESULTS"), "page 1 keeps the paper-exam block")
        val analyticsCompact = text(bytes, page = 2).replace(Regex("\\s+"), "")
        listOf(
            "CBC Analytics Report",
            "LEARNING STREAK",
            "TOTAL XP",
            "CLASS AVERAGE",
            "CLASS PERCENTILE",
            "OFFICIAL SCHOOL RECORD",
        ).forEach { expected ->
            val plain = expected.replace(" ", "")
            assertTrue(
                text(bytes, page = 2).contains(expected) || analyticsCompact.contains(plain),
                "analytics page missing: " + expected,
            )
        }
    }

    @Test
    fun `analytics page carries the engagement values and derived focus`() {
        val bytes = renderer.render(CbcStudentsSpec("Detailed Student CBC Report", branding, listOf(card(detail())), detailed = true))
        val page2 = text(bytes, page = 2)
        val compact = page2.replace(Regex("\\s+"), "")
        assertTrue(compact.contains("LEARNINGSTREAK12Daysinarow"), "learning streak tile missing: " + compact)
        assertTrue(compact.contains("TOTALXP1,840Pointsearned"), "total XP tile missing")
        assertTrue(compact.contains("CLASSAVERAGE78.5%Classmeanthisterm"), "class average tile missing")
        assertTrue(compact.contains("CLASSPERCENTILE80thRelativestanding"), "class percentile tile missing")
        assertTrue(page2.contains("94.2%"), "attendance ring value missing")
        assertTrue(
            compact.contains("LOWEST-RATEDLEARNINGAREADigitalLiteracyAEApproachingExpectations"),
            "the lowest-rated learning area must be named with its level",
        )
        assertTrue(compact.contains("6of7learningareasatMEorabove"), "level-mix summary missing: " + compact)
        assertTrue(compact.contains("2ExceedingME4MeetingAE1Approaching"), "level-mix legend counts missing")
        assertTrue(compact.contains("Planshort,guidedpractice"), "level-based next step missing")
        assertTrue(compact.contains("Page2of2"), "page 2 must be numbered 2 of 2")
    }

    @Test
    fun `analytics page does not repeat the page-1 learning-area table`() {
        val bytes = renderer.render(CbcStudentsSpec("Detailed Student CBC Report", branding, listOf(card(detail())), detailed = true))
        val compact = text(bytes, page = 2).replace(Regex("\\s+"), "")
        assertTrue(!compact.contains("LEARNINGAREAANDEVIDENCE"), "F7: the strand table must not repeat on page 2")
        assertTrue(!compact.contains("CBC1"), "F7: strand codes must not repeat on page 2")
        assertTrue(!compact.contains("PAPEREXAMRESULTS"), "the paper-exam block belongs to page 1 only")
        assertTrue(compact.contains("Page2of2"), "page 2 footer missing")
        assertTrue(text(bytes, page = 1).replace(Regex("\\s+"), "").contains("Page1of2"), "page 1 must be numbered 1 of 2")
    }

    @Test
    fun `learner and parent viewers get an explicit unavailable analytics page`() {
        val bytes = renderer.render(CbcStudentsSpec("Detailed Student CBC Report", branding, listOf(card(null)), detailed = true))
        Loader.loadPDF(bytes).use { document ->
            assertEquals(2, document.numberOfPages, "the page must render without detail analytics")
        }
        val page2 = text(bytes, page = 2)
        val compact = page2.replace(Regex("\\s+"), "")
        assertTrue(compact.contains("Notavailableforthisviewer"), "the unavailable state must be explicit")
        assertEquals(
            4,
            compact.split("Notavailableforthisviewer").size - 1,
            "all four engagement tiles must say the figures are unavailable",
        )
        assertTrue(compact.contains("OFFICIALSCHOOLRECORD"), "sign-off missing on the unavailable page")
        assertTrue(!compact.contains("1,840"), "learners must not see a teacher-only XP figure")
        assertTrue(!compact.contains("78.5%"), "learners must not see the class average")
        assertTrue(!compact.contains("80th"), "learners must not see the class percentile")
        assertTrue(page2.contains("94.2%"), "the learner's own attendance is still shown")
    }

    @Test
    fun `page numbers span the whole detailed document`() {
        val bytes = renderer.render(
            CbcStudentsSpec("Detailed Student CBC Report", branding, listOf(card(detail()), card(detail())), detailed = true),
        )
        Loader.loadPDF(bytes).use { document ->
            assertEquals(4, document.numberOfPages, "two learners at two pages each")
        }
        val compact = text(bytes).replace(Regex("\\s+"), "")
        listOf("Page 1 of 4", "Page 2 of 4", "Page 3 of 4", "Page 4 of 4").forEach { expected ->
            assertTrue(compact.contains(expected.replace(" ", "")), "missing page number: " + expected)
        }
    }

    @Test
    fun `non-detailed cbc report keeps the single card page`() {
        val bytes = renderer.render(CbcStudentsSpec("Student CBC Report", branding, listOf(card(detail()))))
        Loader.loadPDF(bytes).use { document ->
            assertEquals(1, document.numberOfPages, "the plain card stays one page")
        }
        val content = text(bytes)
        assertTrue(!content.contains("CBC Analytics Report"), "the plain card must not gain the analytics page")
        assertTrue(content.replace(Regex("\\s+"), "").contains("Page1of1"), "plain card numbering unchanged")
    }

    /**
     * Design-review helper (A7): writes the rendered sample next to the build output when
     * REPORT_PREVIEW_DIR is set, so the two pages can be reviewed as PDF/PNG. No-op in a
     * normal test run.
     */
    @Test
    fun `detailed cbc sample is available for design review`() {
        val directory = System.getenv("REPORT_PREVIEW_DIR")?.takeIf { it.isNotBlank() } ?: return
        val bytes = renderer.render(CbcStudentsSpec("Detailed Student CBC Report", branding, listOf(card(detail())), detailed = true))
        val path = java.nio.file.Path.of(directory, "detailed-cbc-student.pdf")
        java.nio.file.Files.createDirectories(path.parent)
        java.nio.file.Files.write(path, bytes)
        assertTrue(java.nio.file.Files.size(path) > 1000, "preview PDF was not written")
    }

    @Test
    fun `analytics page renders an empty state when no ratings exist`() {
        val bytes = renderer.render(
            CbcStudentsSpec("Detailed Student CBC Report", branding, listOf(card(detail(), strandRatings = emptyList())), detailed = true),
        )
        Loader.loadPDF(bytes).use { document ->
            assertEquals(2, document.numberOfPages, "the empty state must still be one analytics page")
        }
        val compact = text(bytes, page = 2).replace(Regex("\\s+"), "")
        assertTrue(compact.contains("Nolearning-arearatingsrecorded"), "explicit empty state required")
    }
}
