package com.afrithecus.brainbox.api.report.render

import com.afrithecus.brainbox.api.cbcratings.web.CbcClassReportPayload
import com.afrithecus.brainbox.api.cbcratings.web.CbcStrandMasteryPayload
import com.afrithecus.brainbox.api.cbcratings.web.SubjectTeacherPerformancePayload
import com.afrithecus.brainbox.api.report.CbcClassSpec
import com.afrithecus.brainbox.api.report.ReportBranding
import com.afrithecus.brainbox.api.report.ReportProperties
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A8/Q10: DETAILED_CBC_CLASS keeps the one-page class report and appends only the
 * weak-strand recommendations page, and only when a weak strand carries a recommendation.
 * The second page must never repeat the class strand list (which includes on-track strands).
 */
class CbcClassDetailRendererTest {

    private val branding = ReportBranding(
        schoolName = "Milimani Primary",
        motto = "Nurture - Discover - Excel",
        address = "Nairobi",
        phone = "+254700000000",
        email = "info@milimani.test",
        watermarkText = "Milimani Primary",
    )

    private val properties = ReportProperties(embedLogo = false)
    private val renderer = CbcClassRenderer(properties)

    private val onTrackStrand = CbcStrandMasteryPayload(
        strandCode = "CBC1",
        strandName = "Communication & Collaboration",
        classAverage = 82.0,
        isWeakStrand = false,
    )

    private val weakStrand = CbcStrandMasteryPayload(
        strandCode = "CBC2",
        strandName = "Critical Thinking & Problem Solving",
        classAverage = 38.0,
        studentBreakdown = mapOf(
            "s_1" to "PROFICIENT",
            "s_2" to "DEVELOPING",
            "s_3" to "ADVANCED",
            "s_4" to "MASTER",
        ),
        isWeakStrand = true,
        recommendation = "Reteach problem-solving routines with weekly reasoning tasks",
    )

    private fun classReport(strands: List<CbcStrandMasteryPayload>, studentCount: Int = 25) = CbcClassReportPayload(
        classId = "c_1",
        className = "Grade 5 Lion",
        term = "TERM_2",
        strandMastery = strands,
        overallClassAverage = 60.0,
        subjectTeacherPerformance = listOf(
            SubjectTeacherPerformancePayload("Mathematics", "t_1", "Grace Wanjiku", 70.0, studentCount),
        ),
        studentCount = studentCount,
    )

    private fun render(report: CbcClassReportPayload, detailed: Boolean): ByteArray =
        renderer.render(CbcClassSpec("Detailed Class CBC Report", branding, report, year = 2026, detailed = detailed))

    private fun pageCount(bytes: ByteArray): Int = Loader.loadPDF(bytes).use { it.numberOfPages }

    private fun pageText(bytes: ByteArray, page: Int): String = Loader.loadPDF(bytes).use { document ->
        val stripper = PDFTextStripper()
        stripper.startPage = page
        stripper.endPage = page
        stripper.getText(document)
    }

    private fun compact(value: String): String = value.replace(Regex("\\s+"), "")

    @Test
    fun detailed_class_report_with_a_recommendation_renders_a_second_page() {
        val bytes = render(classReport(listOf(onTrackStrand, weakStrand)), detailed = true)
        assertEquals(2, pageCount(bytes), "detailed class report should append the recommendations page")
        assertTrue(compact(pageText(bytes, 1)).contains("Page1of2"), "page 1 footer must count two pages")
        val pageTwo = pageText(bytes, 2)
        val pageTwoCompact = compact(pageTwo)
        assertTrue(pageTwoCompact.contains("Page2of2"), "page 2 footer must say Page 2 of 2")
        assertTrue(pageTwoCompact.contains("WEAKSTRANDRECOMMENDATIONS"), "recommendations heading missing")
        assertTrue(pageTwo.contains("Critical Thinking & Problem Solving"), "weak strand name missing from page 2")
        assertTrue(pageTwoCompact.contains("LEARNERSNEEDINGSUPPORT"), "learners column missing")
        assertTrue(
            pageTwoCompact.contains("Reteachproblem-solvingroutineswithweeklyreasoningtasks"),
            "recommendation text missing (wraps across two lines)",
        )
        assertTrue(pageTwo.contains("Needs Support"), "weak strand must carry the Needs Support chip")
        assertTrue(pageTwoCompact.contains("OFFICIALSCHOOLRECORD"), "official record line missing")
        assertTrue(pageTwoCompact.contains("EXAMOFFICE"), "Exam Office stamp missing")
    }

    @Test
    fun recommendation_page_never_repeats_the_class_strand_list() {
        val bytes = render(classReport(listOf(onTrackStrand, weakStrand)), detailed = true)
        val pageTwo = pageText(bytes, 2)
        val pageTwoCompact = compact(pageTwo)
        assertTrue(!pageTwo.contains("Communication & Collaboration"), "an on-track strand leaked onto page 2")
        assertTrue(!pageTwoCompact.contains("OnTrack"), "an on-track status leaked onto page 2")
        assertTrue(!pageTwoCompact.contains("STRANDMASTERY"), "the page-1 strand list leaked onto page 2")
    }

    @Test
    fun detailed_class_report_without_weak_strands_stays_one_page() {
        val bytes = render(classReport(listOf(onTrackStrand)), detailed = true)
        assertEquals(1, pageCount(bytes), "no weak strand means no recommendations page")
        assertTrue(compact(pageText(bytes, 1)).contains("Page1of1"))
    }

    @Test
    fun detailed_class_report_with_weak_strand_but_no_recommendation_stays_one_page() {
        val noRecommendation = weakStrand.copy(recommendation = null)
        val bytes = render(classReport(listOf(onTrackStrand, noRecommendation)), detailed = true)
        assertEquals(1, pageCount(bytes), "a missing recommendation must not create an empty page")
        assertTrue(compact(pageText(bytes, 1)).contains("Page1of1"))
    }

    @Test
    fun non_detailed_class_report_stays_one_page_even_when_a_recommendation_exists() {
        val bytes = render(classReport(listOf(onTrackStrand, weakStrand)), detailed = false)
        assertEquals(1, pageCount(bytes), "only DETAILED_CBC_CLASS appends the page")
        assertTrue(compact(pageText(bytes, 1)).contains("Page1of1"))
    }

    @Test
    fun recommendation_page_counts_learners_below_meeting_expectation() {
        // Four learners hold a rating on the weak strand; PROFICIENT and DEVELOPING are below
        // meeting expectation, ADVANCED and MASTER are not. studentCount = 0 makes the payload
        // fall back to the breakdown size (4) as the class denominator.
        val bytes = render(classReport(listOf(onTrackStrand, weakStrand), studentCount = 0), detailed = true)
        val pageTwo = pageText(bytes, 2)
        assertTrue(compact(pageTwo).contains("2of4"), "expected 2 of 4 flagged learners")
        assertTrue(pageTwo.contains("of 4 rated learners"), "the breakdown size should be the learner denominator")
    }

    @Test
    fun recommendation_page_prints_a_dash_when_no_learner_breakdown_exists() {
        val noBreakdown = weakStrand.copy(studentBreakdown = emptyMap())
        val bytes = render(classReport(listOf(onTrackStrand, noBreakdown)), detailed = true)
        val pageTwo = compact(pageText(bytes, 2))
        assertTrue(pageTwo.contains("nolearnerratings"), "missing dash caption")
        assertTrue(pageTwo.contains("nolearner-levelratingsrecorded"), "missing dash caption")
    }
}
