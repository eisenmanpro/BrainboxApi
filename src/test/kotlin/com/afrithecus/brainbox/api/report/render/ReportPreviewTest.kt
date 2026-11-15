package com.afrithecus.brainbox.api.report.render

import com.afrithecus.brainbox.api.cbcratings.web.CbcClassReportPayload
import com.afrithecus.brainbox.api.cbcratings.web.CbcReportCardPayload
import com.afrithecus.brainbox.api.cbcratings.web.CbcStrandMasteryPayload
import com.afrithecus.brainbox.api.cbcratings.web.CbcStrandRatingPayload
import com.afrithecus.brainbox.api.cbcratings.web.PaperExamResultPayload
import com.afrithecus.brainbox.api.cbcratings.web.SubjectTeacherPerformancePayload
import com.afrithecus.brainbox.api.report.CbcClassSpec
import com.afrithecus.brainbox.api.report.CbcStudentCard
import com.afrithecus.brainbox.api.report.CbcStudentDetail
import com.afrithecus.brainbox.api.report.CbcStudentsSpec
import com.afrithecus.brainbox.api.report.ReportArtifact
import com.afrithecus.brainbox.api.report.ReportProperties
import com.afrithecus.brainbox.api.report.ReportRenderer
import com.afrithecus.brainbox.api.report.ReportTitles
import com.afrithecus.brainbox.api.report.TeacherPerformanceSpec
import com.afrithecus.brainbox.api.report.TeacherStrandScore
import com.afrithecus.brainbox.api.report.TeacherSubjectPerformance
import com.afrithecus.brainbox.api.report.TemplateSpec
import com.afrithecus.brainbox.api.report.TraditionalStudentsSpec
import com.afrithecus.brainbox.api.report.web.ReportType
import com.afrithecus.brainbox.api.traditional.web.TraditionalStudentReportDto
import com.afrithecus.brainbox.api.traditional.web.TraditionalSubjectResultDto
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.rendering.PDFRenderer
import org.apache.pdfbox.text.PDFTextStripper
import java.io.File
import java.util.Locale
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A15 - the render-preview harness: one test run writes the complete review set to
 * build/report-previews (PDFs + selected PNG pages + an index). It covers all nine report types,
 * both traditional list orientations (5 portrait, 9 and 13 landscape) and the analysis chart
 * pages, so a reviewer can open one folder after ./gradlew test instead of calling the API.
 *
 * Long list documents are written as full PDFs; PNG previews are generated for the pages a
 * reviewer needs (first pages, repeated-header pages, the totals page and the incomplete section).
 * Card, analysis and template documents get every page as PNG.
 */
class ReportPreviewTest {

    private val renderer = ReportRenderer(ReportProperties(embedLogo = false))
    private val output = File("build/report-previews")

    private data class Preview(val name: String, val bytes: ByteArray, val pngPages: Set<Int>? = null)

    private fun slug(value: String): String =
        value.lowercase(Locale.US).map { if (it.isLetterOrDigit()) it else '-' }
            .joinToString("").replace(Regex("-+"), "-").trim('-')

    private fun specimens(): List<Preview> {
        val previews = mutableListOf<Preview>()
        previews += Preview("traditional-combined-s05", renderer.render(ReportScaleFixtures.scaleSpec(5)))
        previews += Preview("traditional-combined-s09", renderer.render(ReportScaleFixtures.scaleSpec(9)))
        previews += Preview("traditional-combined-s13", renderer.render(ReportScaleFixtures.scaleSpec(13)))
        ReportScaleFixtures.CLASS_TAGS.forEach { tag ->
            previews += Preview(
                "traditional-per-class-s05-" + slug(tag),
                renderer.renderArtifact(ReportArtifact(ReportScaleFixtures.scaleSpec(5), classTag = tag)),
            )
        }
        previews += Preview(
            "traditional-per-class-s13-lion",
            renderer.renderArtifact(ReportArtifact(ReportScaleFixtures.scaleSpec(13), classTag = "Grade 5 Lion")),
        )
        previews += Preview("traditional-student-card", renderer.render(traditionalStudentSpec()))
        previews += Preview("cbc-student-card", renderer.render(cbcStudentsSpec(detailed = false)))
        previews += Preview("cbc-student-detailed", renderer.render(cbcStudentsSpec(detailed = true)))
        previews += Preview("cbc-class", renderer.render(cbcClassSpec(detailed = false)))
        previews += Preview("cbc-class-detailed", renderer.render(cbcClassSpec(detailed = true)))
        previews += Preview("teacher-performance", renderer.render(teacherPerformanceSpec()))
        previews += Preview("grade-analysis-s13", renderer.render(ReportScaleFixtures.analysisSpec()))
        ReportType.entries.forEach { type ->
            previews += Preview(
                "template-" + slug(type.name),
                renderer.render(TemplateSpec(ReportTitles.templateTitle(type), ReportScaleFixtures.branding(), type)),
            )
        }
        return previews
    }

    @Test
    fun writesTheFullReviewSet() {
        output.deleteRecursively()
        output.mkdirs()
        val templates = File(output, "templates").apply { mkdirs() }
        val previews = specimens()
        val index = StringBuilder()
        index.appendLine("# Report v1 render previews")
        index.appendLine()
        index.appendLine("Generated by ReportPreviewTest (A15) from the A17 500-learner fixture.")
        index.appendLine()
        index.appendLine("| preview | pages | PNG pages |")
        index.appendLine("|---|---|---|")

        previews.forEach { preview ->
            val isTemplate = preview.name.startsWith("template-")
            val folder = if (isTemplate) templates else output
            val pdf = File(folder, preview.name + ".pdf")
            pdf.writeBytes(preview.bytes)
            val (pages, pngPages) = writePngs(preview, folder)
            index.appendLine("| " + preview.name + " | " + pages + " | " + pngPages + " |")
            assertTrue(pdf.length() > 1000, "empty preview " + preview.name)
        }
        File(output, "index.md").writeText(index.toString())

        val pdfs = output.walkTopDown().filter { it.isFile && it.extension == "pdf" }.toList()
        assertEquals(previews.size, pdfs.size, "one PDF per preview")
        assertEquals(ReportType.entries.size, pdfs.count { it.name.startsWith("template-") }, "one template per type")
        assertEquals(13, pages(File(output, "traditional-combined-s05.pdf")))
        assertEquals(17, pages(File(output, "traditional-combined-s09.pdf")))
        assertEquals(15, pages(File(output, "traditional-combined-s13.pdf")))
        assertEquals(6, pages(File(output, "grade-analysis-s13.pdf")))
        assertEquals(2, pages(File(output, "cbc-student-detailed.pdf")))
        assertEquals(2, pages(File(output, "cbc-class-detailed.pdf")))
        val pngs = output.walkTopDown().count { it.isFile && it.extension == "png" }
        assertTrue(pngs >= 24, "expected at least 24 preview PNGs, got " + pngs)
        val indexText = File(output, "index.md").readText()
        previews.forEach { preview -> assertTrue(indexText.contains(preview.name), "index missing " + preview.name) }
        assertTrue(indexText.contains("500-learner fixture"))
    }

    private fun pages(file: File): Int = Loader.loadPDF(file).use { it.numberOfPages }

    private fun writePngs(preview: Preview, folder: File): Pair<Int, String> {
        Loader.loadPDF(preview.bytes).use { document ->
            val selection = preview.pngPages ?: selectPages(document)
            val raster = PDFRenderer(document)
            selection.forEach { zeroBased ->
                ImageIO.write(raster.renderImageWithDPI(zeroBased, 100f), "png", File(folder, preview.name + "-" + (zeroBased + 1) + ".png"))
            }
            return document.numberOfPages to selection.map { (it + 1).toString() }.joinToString(", ")
        }
    }

    private fun selectPages(document: PDDocument): Set<Int> {
        val pageCount = document.numberOfPages
        if (pageCount <= 8) return (0 until pageCount).toSet()
        val selected = linkedSetOf(0, 1, pageCount - 2, pageCount - 1)
        for (index in 0 until pageCount) {
            val text = PDFTextStripper().apply { startPage = index + 1; endPage = index + 1 }.getText(document)
            if (text.contains("OVERALL") || text.contains("STUDENTS WITH INCOMPLETE MARKS")) selected += index
        }
        return selected.filter { it in 0 until pageCount }.toSet()
    }

    private fun traditionalResults(): List<TraditionalSubjectResultDto> = listOf(
        TraditionalSubjectResultDto("Mathematics", 78, 100, 78.0, "ME"),
        TraditionalSubjectResultDto("English", 29, 40, 72.5, "ME"),
        TraditionalSubjectResultDto("Kiswahili", 33, 40, 82.5, "EE"),
        TraditionalSubjectResultDto("Integrated Science", 34, 50, 68.0, "ME"),
        TraditionalSubjectResultDto("Social Studies", 33, 50, 66.0, "ME"),
        TraditionalSubjectResultDto("CRE", 21, 30, 70.0, "ME"),
        TraditionalSubjectResultDto("Agriculture", 31, 50, 62.0, "AE"),
        TraditionalSubjectResultDto("Creative Arts", 39, 50, 78.0, "ME"),
    )

    private fun traditionalStudentSpec(): TraditionalStudentsSpec {
        val report = TraditionalStudentReportDto(
            studentId = "learner-11",
            studentName = "Amina Mwangi",
            admissionNumber = "ADM-2019-011",
            gradeLevel = "Grade 5",
            classTag = "Grade 5 Lion",
            term = "Term 2",
            year = 2026,
            subjectResults = traditionalResults(),
            totalScore = 298,
            overallPercentage = 72.3,
            overallGrade = "ME",
            classPosition = 4,
            totalStudentsInClass = 20,
            teacherRemarks = "Amina is a consistent worker who participates well in class. Keep strengthening Agriculture through the practical activities.",
            schoolName = "Milimani Primary School",
            classTeacherName = "Grace Wanjiku",
        )
        return TraditionalStudentsSpec("Grade 5 Term 2 2026 - Student Report", ReportScaleFixtures.branding(), listOf(report))
    }

    private fun cbcCard(): CbcReportCardPayload = CbcReportCardPayload(
        studentName = "Amina Mwangi",
        term = "Term 2",
        strandRatings = listOf(
            CbcStrandRatingPayload("ENG2.1", "Reads age-appropriate texts fluently", "MEETING", strandName = "English"),
            CbcStrandRatingPayload("KISW3.2", "Writes a structured narrative", "EXCEEDING", strandName = "Kiswahili"),
            CbcStrandRatingPayload("MAT1.4", "Applies fractions to real problems", "APPROACHING", strandName = "Mathematics"),
            CbcStrandRatingPayload("SCI2.3", "Records observations accurately", "MEETING", strandName = "Integrated Science"),
            CbcStrandRatingPayload("SST4.1", "Locates counties on a map", "MEETING", strandName = "Social Studies"),
            CbcStrandRatingPayload("CRE1.2", "Describes values in stories", "EXCEEDING", strandName = "CRE"),
        ),
        teacherComments = "Amina reads with confidence and supports her group well. She should practise fractions a little more each week.",
        attendancePercentage = 93.5,
        overallGrade = "ME",
        paperExamResults = listOf("Mathematics" to 78.0, "English" to 72.5, "Kiswahili" to 82.5, "Integrated Science" to 68.0, "Social Studies" to 66.0, "CRE" to 70.0, "Agriculture" to 62.0, "Creative Arts" to 78.0).map { (subject, percentage) ->
            PaperExamResultPayload(
                subject = subject,
                score = percentage,
                maxScore = 100.0,
                percentage = percentage,
                grade = when {
                    percentage >= 80.0 -> "EE"
                    percentage >= 65.0 -> "ME"
                    percentage >= 50.0 -> "AE"
                    else -> "BE"
                },
                examId = "exam-grade-5-term-2",
                examTitle = "End Term 2 2026 - Grade 5",
                term = "Term 2",
                year = 2026,
                examDate = "15 Nov 2026",
            )
        },
        gradeLevel = "Grade 5",
        admissionNumber = "ADM-2019-011",
    )

    private fun cbcStudentsSpec(detailed: Boolean): CbcStudentsSpec {
        val card = CbcStudentCard(
            studentId = "learner-11",
            studentName = "Amina Mwangi",
            gradeLevel = "Grade 5",
            term = "Term 2",
            card = cbcCard(),
            detail = if (detailed) CbcStudentDetail(learningStreakDays = 12, totalXp = 450, classAverageScore = 71.5, classPercentile = 78) else null,
            year = 2026,
        )
        return CbcStudentsSpec(
            if (detailed) "Detailed Student CBC Report" else "Student CBC Report",
            ReportScaleFixtures.branding(),
            listOf(card),
            detailed = detailed,
        )
    }

    private fun cbcClassReport(): CbcClassReportPayload = CbcClassReportPayload(
        classId = "class-grade-5-lion",
        className = "Grade 5 Lion",
        term = "Term 2",
        strandMastery = listOf(
            CbcStrandMasteryPayload("MATH1.4", "Fractions and decimals", 43.3, mapOf("ME" to "PROFICIENT"), true, "Reteach equivalent fractions with paper strips; 8 learners need support."),
            CbcStrandMasteryPayload("ENG2.1", "Reading fluency", 71.2, mapOf("EE" to "EXCEEDING"), false, null),
            CbcStrandMasteryPayload("KISW3.2", "Narrative writing", 68.4, emptyMap(), false, null),
            CbcStrandMasteryPayload("SCI2.3", "Recording observations", 64.0, emptyMap(), false, null),
            CbcStrandMasteryPayload("SST4.1", "County mapping", 72.6, emptyMap(), false, null),
            CbcStrandMasteryPayload("CRA5.2", "Creative performance", 69.1, emptyMap(), false, null),
        ),
        overallClassAverage = 61.2,
        subjectTeacherPerformance = listOf(
            SubjectTeacherPerformancePayload("Mathematics", "teacher-1", "Mr. James Otieno", 58.4, 20),
            SubjectTeacherPerformancePayload("English", "teacher-2", "Grace Wanjiku", 74.2, 20),
            SubjectTeacherPerformancePayload("Kiswahili", "teacher-3", "Peter Kamau", 68.9, 20),
            SubjectTeacherPerformancePayload("Integrated Science", "teacher-4", "Faith Muthoni", 63.1, 20),
            SubjectTeacherPerformancePayload("Social Studies", "teacher-5", "Alice Njeri", 66.5, 20),
        ),
        generatedAt = 1_760_000_000_000L,
        studentCount = 30,
    )

    private fun cbcClassSpec(detailed: Boolean): CbcClassSpec = CbcClassSpec(
        if (detailed) "Detailed Class CBC Report" else "Class CBC Report",
        ReportScaleFixtures.branding(),
        cbcClassReport(),
        year = 2026,
        detailed = detailed,
    )

    private fun teacherPerformanceSpec(): TeacherPerformanceSpec = TeacherPerformanceSpec(
        title = "Teacher Performance Report",
        branding = ReportScaleFixtures.branding(),
        teacherName = "Grace Wanjiku",
        className = "Grade 5 Lion",
        term = "Term 2",
        year = 2026,
        teacherAverage = 74.2,
        classAverage = 66.5,
        subjects = listOf(
            TeacherSubjectPerformance(
                subject = "English",
                average = 74.2,
                learners = 20,
                strands = listOf(
                    TeacherStrandScore("ENG2.1", "Reading fluency", 76.8),
                    TeacherStrandScore("ENG2.2", "Writing composition", 71.5),
                    TeacherStrandScore("ENG2.3", "Listening and speaking", 79.4),
                ),
            ),
        ),
        subjectsInClass = 5,
        learners = 20,
        topSubject = "English",
        topSubjectAverage = 74.2,
    )
}

