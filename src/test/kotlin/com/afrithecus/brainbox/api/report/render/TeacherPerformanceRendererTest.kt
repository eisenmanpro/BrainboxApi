package com.afrithecus.brainbox.api.report.render

import com.afrithecus.brainbox.api.report.ReportBranding
import com.afrithecus.brainbox.api.report.ReportProperties
import com.afrithecus.brainbox.api.report.TeacherPerformanceSpec
import com.afrithecus.brainbox.api.report.TeacherStrandScore
import com.afrithecus.brainbox.api.report.TeacherSubjectPerformance
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A6: the teacher performance report renders one teacher's own delivery, not the class view. */
class TeacherPerformanceRendererTest {

    private val renderer = TeacherPerformanceRenderer(ReportProperties(embedLogo = false))

    private val branding = ReportBranding(
        schoolName = "Riverside Academy",
        motto = "Nurture - Discover - Excel",
        address = "P.O. Box 2145, Nairobi",
        phone = "+254 700 112 233",
        email = "info@riverside.ac.ke",
        watermarkText = "Riverside Academy",
    )

    private fun subject(name: String, average: Double, learners: Int, strands: List<Pair<String, Double>>) =
        TeacherSubjectPerformance(
            subject = name,
            average = average,
            learners = learners,
            strands = strands.map { (code, score) -> TeacherStrandScore(code, code, score) },
        )

    private fun spec() = TeacherPerformanceSpec(
        title = "Teacher Performance Report",
        branding = branding,
        teacherName = "Mr. James Otieno",
        className = "Grade 4 East",
        term = "TERM_3",
        year = 2026,
        teacherAverage = 73.5,
        classAverage = 75.3,
        subjects = listOf(
            subject("Mathematics", 76.0, 25, listOf("CBC2" to 66.0, "CBC5" to 81.0)),
            subject("Science & Technology", 71.0, 25, listOf("CBC2" to 64.0, "CBC7" to 74.0)),
        ),
        subjectsInClass = 4,
        learners = 25,
        topSubject = "Mathematics",
        topSubjectAverage = 76.0,
    )

    private fun load(bytes: ByteArray): Pair<Int, String> = Loader.loadPDF(bytes).use { document ->
        document.numberOfPages to PDFTextStripper().getText(document)
    }

    @Test
    fun `teacher report renders the v1 sections with teacher-specific figures`() {
        val (pages, text) = load(renderer.render(spec()))
        assertEquals(1, pages)
        val compact = text.replace(Regex("\\s+"), "")
        assertTrue(compact.contains("TeacherPerformanceReport"), "title missing: " + compact.take(200))
        assertTrue(compact.contains("MR.JAMESOTIENO"), "teacher name missing")
        assertTrue(compact.contains("SUBJECTSTAUGHT"), "KPI label missing")
        assertTrue(compact.contains("73.5%"), "teacher average missing")
        assertTrue(compact.contains("75.3%"), "class average missing")
        assertTrue(compact.contains("2of4subjects"), "coverage missing")
        assertTrue(compact.contains("Mathematics"), "top subject missing")
        assertTrue(compact.contains("CBC2"), "teacher-specific strand code missing")
        assertTrue(compact.contains("CBC7"), "second subject strand missing")
        assertTrue(compact.contains("66.0%"), "strand percentage missing")
        assertTrue(compact.contains("EXAMOFFICE"), "Exam Office stamp missing (D5)")
        assertTrue(compact.contains("OFFICIALSCHOOLRECORD"), "official record line missing")
        assertFalse(compact.contains("AcademicOffice"), "no named signatory office line (D5)")
    }

    @Test
    fun `teacher report renders explicit empty state when the teacher teaches nothing in this class`() {
        val empty = spec().copy(subjects = emptyList(), teacherAverage = 0.0, topSubject = null, topSubjectAverage = 0.0)
        val (pages, text) = load(renderer.render(empty))
        assertEquals(1, pages)
        val compact = text.replace(Regex("\\s+"), "")
        assertTrue(compact.contains("Nosubjectassignmentsrecordedforthisclassthis"), "empty state missing")
        assertTrue(compact.contains("0of4subjects"), "coverage zero missing")
        assertFalse(compact.contains("CBC2"), "empty report must not leak class strand data")
    }

    @Test
    fun `many subjects compress and label the truncation instead of overflowing`() {
        val subjects = (1..6).map { index ->
            subject(
                "Subject " + index,
                90.0 - index * 4,
                25,
                (1..4).map { strand -> "CBC" + strand to 80.0 - strand * 3 },
            )
        }
        val (pages, text) = load(renderer.render(spec().copy(subjects = subjects, subjectsInClass = 6)))
        assertEquals(1, pages)
        val compact = text.replace(Regex("\\s+"), "")
        assertTrue(compact.contains("Showing"), "truncation not labelled")
        assertTrue(compact.contains("OFFICIALSCHOOLRECORD"), "footer/sign-off clamped away")
        assertTrue(compact.contains("Subject1"), "first subject missing")
    }
}
