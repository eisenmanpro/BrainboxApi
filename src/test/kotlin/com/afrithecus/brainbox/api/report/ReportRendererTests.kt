package com.afrithecus.brainbox.api.report

import com.afrithecus.brainbox.api.cbcratings.web.CbcClassReportPayload
import com.afrithecus.brainbox.api.cbcratings.web.CbcReportCardPayload
import com.afrithecus.brainbox.api.cbcratings.web.CbcStrandMasteryPayload
import com.afrithecus.brainbox.api.cbcratings.web.CbcStrandRatingPayload
import com.afrithecus.brainbox.api.cbcratings.web.SubjectTeacherPerformancePayload
import com.afrithecus.brainbox.api.traditional.model.ExamTerm
import com.afrithecus.brainbox.api.traditional.model.TraditionalExamStatus
import com.afrithecus.brainbox.api.traditional.model.TraditionalSubjectType
import com.afrithecus.brainbox.api.traditional.web.ClassGradeAnalysisDto
import com.afrithecus.brainbox.api.traditional.web.StudentGradeRowDto
import com.afrithecus.brainbox.api.traditional.web.SubjectConfigDto
import com.afrithecus.brainbox.api.traditional.web.TraditionalExamDto
import com.afrithecus.brainbox.api.traditional.web.TraditionalGradeAnalysisDto
import com.afrithecus.brainbox.api.traditional.web.TraditionalStudentReportDto
import com.afrithecus.brainbox.api.report.web.ReportType
import com.afrithecus.brainbox.api.traditional.web.TraditionalSubjectResultDto
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

/** The server renderer produces real, branded, text-extractable PDFs per family. */
class ReportRendererTests {

    private val branding = ReportBranding(
        schoolName = "Alliance High School",
        motto = "Knowledge is Power",
        address = "Nairobi",
        phone = "+254700000000",
        email = "info@alliance.test",
        primaryColor = "#1F2A44",
        watermarkText = "Alliance High School",
    )

    private val renderer = ReportRenderer(ReportProperties(embedLogo = false))

    private val subjects = listOf(
        SubjectConfigDto("MATH", "Mathematics", 100, TraditionalSubjectType.SINGLE),
        SubjectConfigDto("ENG", "English", 100, TraditionalSubjectType.SINGLE),
    )

    private fun exam() = TraditionalExamDto(
        examId = "exam_1",
        title = "End Term 2 2026",
        term = ExamTerm.TERM_2,
        gradeLevel = "Grade 4",
        year = 2026,
        status = TraditionalExamStatus.PUBLISHED,
        subjects = subjects,
        createdAt = System.currentTimeMillis(),
    )

    private fun row(rank: Int, name: String, math: Int, eng: Int) = StudentGradeRowDto(
        rank = rank,
        studentId = "s_" + rank,
        studentName = name,
        classTag = "Grade 4 East",
        subjects = listOf(
            TraditionalSubjectResultDto("Mathematics", math, 100, math.toDouble(), if (math >= 80) "EE" else "ME"),
            TraditionalSubjectResultDto("English", eng, 100, eng.toDouble(), if (eng >= 80) "EE" else "ME"),
        ),
        totalPercentage = (math + eng).toDouble(),
        totalScore = math + eng,
        overallGrade = "ME",
    )

    private fun text(bytes: ByteArray): String = Loader.loadPDF(bytes).use { PDFTextStripper().getText(it) }

    private fun assertPdf(bytes: ByteArray, mustContain: List<String> = emptyList()) {
        assertTrue(bytes.size > 500, "PDF should not be empty")
        assertTrue(String(bytes, 0, 5, Charsets.ISO_8859_1) == "%PDF-", "missing PDF header")
        val content = text(bytes)
        assertTrue(content.contains("Alliance High School"), "branding school name missing")
        mustContain.forEach { assertTrue(content.contains(it), "missing text: " + it) }
    }

    @Test
    fun `grade table pdf carries branding and rows`() {
        val spec = GradeTableSpec("End Term 2 2026", branding, exam(), listOf(row(1, "Alice Mwangi", 80, 60), row(2, "Bob Otieno", 70, 90)))
        assertPdf(renderer.render(spec), listOf("Mathematics", "English", "Alice Mwangi", "Bob Otieno"))
    }

    @Test
    fun `per-class and analysis pdfs render`() {
        val table = GradeTableSpec("End Term 2 2026", branding, exam(), listOf(row(1, "Alice Mwangi", 80, 60)), perClass = true)
        assertPdf(renderer.render(table), listOf("Grade 4 East"))
        val analysis = TraditionalGradeAnalysisDto(
            examId = "exam_1",
            gradeLevel = "Grade 4",
            term = "Term 2",
            year = 2026,
            classes = listOf(
                ClassGradeAnalysisDto("Grade 4 East", "Mr Kimani", mapOf("Mathematics" to 75.0), mapOf("Mathematics" to 1), 75.0, 1),
            ),
        )
        assertPdf(renderer.render(GradeAnalysisSpec("Grade 4", branding, analysis)), listOf("Mr Kimani"))
    }

    @Test
    fun `traditional and cbc student cards render`() {
        val report = TraditionalStudentReportDto(
            studentId = "s_1",
            studentName = "Alice Mwangi",
            admissionNumber = "ADM-001",
            gradeLevel = "Grade 4",
            classTag = "Grade 4 East",
            term = "Term 2",
            year = 2026,
            subjectResults = listOf(TraditionalSubjectResultDto("Mathematics", 80, 100, 80.0, "EE")),
            totalScore = 80,
            overallPercentage = 80.0,
            overallGrade = "EE",
            classPosition = 1,
            totalStudentsInClass = 2,
            teacherRemarks = "Excellent work",
            schoolName = "Alliance High School",
        )
        assertPdf(renderer.render(TraditionalStudentsSpec("Student Report", branding, listOf(report))), listOf("Alice Mwangi", "Excellent work"))

        val card = CbcStudentsSpec(
            "Student CBC Report",
            branding,
            listOf(
                CbcStudentCard(
                    "s_1",
                    "Alice Mwangi",
                    "Grade 4",
                    "Term 2",
                    CbcReportCardPayload(
                        studentName = "Alice Mwangi",
                        term = "Term 2",
                        strandRatings = listOf(CbcStrandRatingPayload("ENG", "Reads fluently", "MEETING")),
                        teacherComments = "Good progress",
                        attendancePercentage = 95.0,
                        overallGrade = "ME",
                        gradeLevel = "Grade 4",
                    ),
                ),
            ),
        )
        assertPdf(renderer.render(card), listOf("Reads fluently", "Good progress"))

        val classReport = CbcClassReportPayload(
            classId = "c_1",
            className = "Grade 4 East",
            term = "Term 2",
            strandMastery = listOf(CbcStrandMasteryPayload("ENG", "English", 70.0, emptyMap(), false, null)),
            overallClassAverage = 70.0,
            subjectTeacherPerformance = listOf(SubjectTeacherPerformancePayload("Mathematics", "t_1", "Mr Kimani", 70.0, 20)),
        )
        assertPdf(renderer.render(CbcClassSpec("Class CBC Report", branding, classReport)), listOf("Mr Kimani", "English"))
    }

    @Test
    fun `blank templates render for every report type`() {
        ReportType.entries.forEach { type ->
            val bytes = renderer.render(TemplateSpec("Template - " + type.name, branding, type))
            assertPdf(bytes)
            val content = text(bytes)
            assertTrue(content.contains("Template - " + type.name), "template title missing for " + type)
            assertTrue(content.contains("Blank template"), "template subtitle missing for " + type)
        }
        val cbc = text(renderer.render(TemplateSpec("Template - CBC", branding, ReportType.CBC_STUDENT)))
        assertTrue(cbc.contains("CBC Learning Areas"), "CBC student template sections missing")
        assertTrue(cbc.contains("Communication & Collaboration"), "CBC strand names missing")
        val detailed = text(renderer.render(TemplateSpec("Template - CBC", branding, ReportType.DETAILED_CBC_CLASS)))
        assertTrue(detailed.contains("Weak Strand Recommendations"), "detailed class section missing")
    }

    @Test
    fun `detailed cbc student renders a second analytics page`() {
        val card = CbcReportCardPayload(
            studentName = "Alice Mwangi",
            term = "Term 2",
            strandRatings = listOf(CbcStrandRatingPayload("ENG", "Reads fluently", "MEETING")),
            teacherComments = "Good progress",
            attendancePercentage = 95.0,
            overallGrade = "ME",
            gradeLevel = "Grade 4",
        )
        val student = CbcStudentCard(
            "s_1", "Alice Mwangi", "Grade 4", "Term 2", card,
            CbcStudentDetail(learningStreakDays = 12, totalXp = 450, classAverageScore = 71.5, classPercentile = 80),
        )
        val detailed = CbcStudentsSpec("Detailed Student CBC Report", branding, listOf(student), detailed = true)
        assertPdf(
            renderer.render(detailed),
            listOf("CBC Analytics Report", "Attendance & Engagement", "CBC Assessment Levels", "Reads fluently", "12 days", "450 XP", "71.5%", "80th"),
        )
        val plain = text(renderer.render(CbcStudentsSpec("Student CBC Report", branding, listOf(student))))
        assertTrue(!plain.contains("CBC Assessment Levels"), "plain CBC report must not include the analytics page")
    }

    @Test
    fun `detailed cbc class separates weak strand recommendations`() {
        val classReport = CbcClassReportPayload(
            classId = "c_1",
            className = "Grade 4 East",
            term = "Term 2",
            strandMastery = listOf(
                CbcStrandMasteryPayload("ENG", "English", 70.0, emptyMap(), false, "Keep reading daily"),
                CbcStrandMasteryPayload("MATH", "Mathematics", 40.0, emptyMap(), true, "Reteach fractions"),
            ),
            overallClassAverage = 55.0,
            subjectTeacherPerformance = emptyList(),
        )
        val detailed = text(renderer.render(CbcClassSpec("Detailed Class CBC Report", branding, classReport, detailed = true)))
        assertTrue(detailed.contains("Weak Strand Recommendations"), "detailed class report needs the separate section")
        assertTrue(detailed.contains("Reteach fractions"), "weak strand recommendation missing")
    }
}
