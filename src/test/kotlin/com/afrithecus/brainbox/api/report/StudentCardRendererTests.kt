package com.afrithecus.brainbox.api.report

import com.afrithecus.brainbox.api.cbcratings.web.CbcClassReportPayload
import com.afrithecus.brainbox.api.cbcratings.web.CbcReportCardPayload
import com.afrithecus.brainbox.api.cbcratings.web.CbcStrandMasteryPayload
import com.afrithecus.brainbox.api.cbcratings.web.CbcStrandRatingPayload
import com.afrithecus.brainbox.api.cbcratings.web.PaperExamResultPayload
import com.afrithecus.brainbox.api.cbcratings.web.SubjectTeacherPerformancePayload
import com.afrithecus.brainbox.api.report.render.CbcClassRenderer
import com.afrithecus.brainbox.api.report.render.CbcStudentRenderer
import com.afrithecus.brainbox.api.report.render.TraditionalStudentRenderer
import com.afrithecus.brainbox.api.traditional.web.TraditionalStudentReportDto
import com.afrithecus.brainbox.api.traditional.web.TraditionalSubjectResultDto
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

/** A3/A4/A5: the v1 student/class cards render real, text-extractable PDFs. */
class StudentCardRendererTests {

    private val branding = ReportBranding(
        schoolName = "Milimani Primary",
        motto = "Nurture - Discover - Excel",
        address = "Nairobi",
        phone = "+254700000000",
        email = "info@milimani.test",
        watermarkText = "Milimani Primary",
    )

    private val properties = ReportProperties(embedLogo = false)

    private fun text(bytes: ByteArray): String = Loader.loadPDF(bytes).use { PDFTextStripper().getText(it) }

    private fun assertCards(bytes: ByteArray, pages: Int, mustContain: List<String> = emptyList()) {
        Loader.loadPDF(bytes).use { document ->
            assertTrue(document.numberOfPages == pages, "expected " + pages + " page(s), got " + document.numberOfPages)
            val content = PDFTextStripper().getText(document)
            val compact = content.replace(Regex("\\s+"), "")
            assertTrue(content.contains("Milimani Primary"), "branding missing")
            mustContain.forEach { expected ->
                val plain = expected.replace(" ", "")
                assertTrue(content.contains(expected) || compact.contains(plain), "missing text: " + expected)
            }
        }
    }

    private fun traditionalReport(): TraditionalStudentReportDto = TraditionalStudentReportDto(
        studentId = "s_1",
        studentName = "Aisha Mwangi",
        admissionNumber = "500101",
        gradeLevel = "5",
        classTag = "Lion",
        term = "Term 2",
        year = 2026,
        subjectResults = listOf(
            TraditionalSubjectResultDto("English", 84, 100, 84.0, "EE"),
            TraditionalSubjectResultDto("Mathematics", 71, 100, 71.0, "AE"),
            TraditionalSubjectResultDto("Kiswahili", 90, 100, 90.0, "EE"),
        ),
        totalScore = 245,
        overallPercentage = 81.7,
        overallGrade = "EE",
        classPosition = 2,
        totalStudentsInClass = 20,
        teacherRemarks = "Aisha has had a strong term and reads confidently.",
        schoolName = "Milimani Primary",
        classTeacherName = "Grace Wanjiku",
    )

    @Test
    fun `traditional card renders the v1 sections and the class teacher`() {
        val spec = TraditionalStudentsSpec("Student Traditional Exam Report", branding, listOf(traditionalReport()))
        val bytes = TraditionalStudentRenderer(properties).render(spec)
        assertCards(
            bytes,
            1,
            listOf(
                "Term Report Card",
                "Aisha Mwangi",
                "Lion",
                "500101",
                "ACADEMIC PERFORMANCE",
                "Mathematics",
                "71%",
                "AE",
                "OVERALL ACHIEVEMENT",
                "81.7%",
                "CLASS POSITION",
                "Grace Wanjiku",
                "CLASS TEACHER",
            ),
        )
    }

    private fun cbcCard(papers: List<PaperExamResultPayload> = emptyList()): CbcStudentCard = CbcStudentCard(
        studentId = "s_1",
        studentName = "Aisha Mwangi",
        gradeLevel = "Grade 5",
        term = "TERM_2",
        year = 2026,
        card = CbcReportCardPayload(
            studentName = "Aisha Mwangi",
            term = "TERM_2",
            strandRatings = listOf(
                CbcStrandRatingPayload("CBC1", "Expresses ideas clearly and listens to others", "EXCEEDING", strandName = "Communication & Collaboration"),
                CbcStrandRatingPayload("CBC2", "Applies reasoning to unfamiliar tasks", "MEETING", strandName = "Critical Thinking & Problem Solving"),
            ),
            teacherComments = "Aisha reads widely and contributes confidently in class.",
            attendancePercentage = 95.0,
            overallGrade = "ME",
            paperExamResults = papers,
            gradeLevel = "Grade 5",
            admissionNumber = "500101",
        ),
    )

    private fun paperRows(count: Int): List<PaperExamResultPayload> = (1..count).map { index ->
        PaperExamResultPayload(
            subject = "Subject " + index,
            score = 60.0 + index,
            maxScore = 100.0,
            percentage = 60.0 + index,
            grade = "ME",
            examId = "exam_1",
            examTitle = "End Term 2 2026",
            term = "Term 2",
            year = 2026,
            examDate = "15 Nov 2026",
        )
    }

    @Test
    fun `cbc card shows learning-area names and the single paper exam block`() {
        val spec = CbcStudentsSpec("Student CBC Report", branding, listOf(cbcCard(paperRows(3))))
        val content = text(CbcStudentRenderer(properties).render(spec))
        val compact = content.replace(Regex("\\s+"), "")
        assertTrue(content.contains("Communication & Collaboration"), "D1 learning-area name missing")
        assertTrue(content.contains("Critical Thinking & Problem Solving"), "D1 learning-area name missing")
        assertTrue(!content.contains("CBC1\n"), "the code must not stand in for the name")
        assertTrue(compact.contains("PAPEREXAMRESULTS"), "paper block missing")
        assertTrue(content.contains("End Term 2 2026"), "paper exam label missing")
        assertTrue(content.contains("15 Nov 2026"), "paper exam date missing")
        assertTrue(content.contains("61 / 100"), "paper row missing")
        assertTrue(content.contains("Aisha Mwangi"), "learner name missing")
    }

    @Test
    fun `cbc card has an explicit empty state and overflows thirteen papers into two columns`() {
        val empty = text(CbcStudentRenderer(properties).render(CbcStudentsSpec("Student CBC Report", branding, listOf(cbcCard()))))
        assertTrue(empty.contains("No paper-exam marks recorded."), "explicit empty state missing")
        val many = text(CbcStudentRenderer(properties).render(CbcStudentsSpec("Student CBC Report", branding, listOf(cbcCard(paperRows(13))))))
        (1..13).forEach { index -> assertTrue(many.contains("Subject " + index), "paper row " + index + " missing") }
        Loader.loadPDF(CbcStudentRenderer(properties).render(CbcStudentsSpec("Student CBC Report", branding, listOf(cbcCard(paperRows(13)))))).use {
            assertTrue(it.numberOfPages == 1, "the paper block must overflow in columns, not a continuation page")
        }
    }

    @Test
    fun `cbc class card renders the four KPIs, strand mastery and focus callout`() {
        val report = CbcClassReportPayload(
            classId = "c_1",
            className = "Grade 5 Lion",
            term = "TERM_2",
            strandMastery = listOf(
                CbcStrandMasteryPayload("CBC1", "Communication & Collaboration", 82.0, emptyMap(), false, null),
                CbcStrandMasteryPayload("CBC2", "Critical Thinking & Problem Solving", 38.0, emptyMap(), true, "Reteach problem-solving routines"),
            ),
            overallClassAverage = 60.0,
            subjectTeacherPerformance = listOf(SubjectTeacherPerformancePayload("Mathematics", "t_1", "Grace Wanjiku", 70.0, 20)),
            studentCount = 20,
        )
        val content = text(CbcClassRenderer(properties).render(CbcClassSpec("Class CBC Report", branding, report)))
        val compact = content.replace(Regex("\\s+"), "")
        listOf(
            "Class CBC Report",
            "OVERALL AVERAGE",
            "STRANDS ASSESSED",
            "ON TRACK",
            "NEEDS SUPPORT",
            "20 learners in class",
            "Communication & Collaboration",
            "Critical Thinking & Problem Solving",
            "Grace Wanjiku",
            "FOCUS FOR NEXT TERM",
            "Reteach problem-solving routines",
            "1 STRAND BELOW TARGET",
        ).forEach { expected ->
            val plain = expected.replace(" ", "")
            assertTrue(content.contains(expected) || compact.contains(plain), "missing class-card text: " + expected)
        }
    }
}
