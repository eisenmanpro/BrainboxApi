package com.afrithecus.brainbox.api.report.render

import com.afrithecus.brainbox.api.report.GradeAnalysisSpec
import com.afrithecus.brainbox.api.report.ReportBranding
import com.afrithecus.brainbox.api.report.ReportProperties
import com.afrithecus.brainbox.api.traditional.web.ClassGradeAnalysisDto
import com.afrithecus.brainbox.api.traditional.web.GradingBandDto
import com.afrithecus.brainbox.api.traditional.web.GradingConfigDto
import com.afrithecus.brainbox.api.traditional.web.SubjectTeacherContributionDto
import com.afrithecus.brainbox.api.traditional.web.TeacherContributionDto
import com.afrithecus.brainbox.api.traditional.web.TraditionalGradeAnalysisDto
import com.afrithecus.brainbox.api.traditional.web.UnassignedContributionDto
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.text.PDFTextStripper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A10/A21 renderer tests (plan §2.5D + §2.5I). Synthetic analysis payloads only — the
 * renderer takes the already-computed class means/positions (D3/D10/D11) and subject
 * teacher contributions (D13/D14), so no Spring context or database is involved.
 *
 * Extraction note (F20/A14): the embedded Lato font currently drops the "ti" ligature when
 * the PDF text is extracted ("positions" extracts as "posions", "Otieno" as "Oeno"). That is
 * the known font/ToUnicode extraction defect tracked by A14, not a rendering defect, so the
 * assertions below either avoid substrings containing "ti" or compare whitespace-flattened
 * labels. A14 fixes ReportTheme; this renderer's page content is already correct.
 */
class GradeAnalysisRendererTest {

    private val renderer = GradeAnalysisRenderer(ReportProperties(embedLogo = false))

    // --------------------------------------------------------------- fixture A

    private val subjectsA = listOf("Mathematics", "English", "Kiswahili", "Integrated Science", "Social Studies")

    private fun fixtureA(studentCounts: Boolean = true): TraditionalGradeAnalysisDto {
        val rows = listOf(
            classRow("G5 LION", "Mr. James Otieno", listOf(80.0, 60.0, 70.0, 75.0, 65.0), 2, 350.0, 2, if (studentCounts) 10 else 0),
            classRow("G5 BUFFALO", "Ms. Grace Wairimu", listOf(60.0, 50.0, 55.0, 65.0, 50.0), 3, 280.0, 3, if (studentCounts) 20 else 0),
            classRow("G5 ELEPHANT", "Mr. Peter Kamau", listOf(50.0, 40.0, 45.0, 55.0, 40.0), 4, 230.0, 4, if (studentCounts) 30 else 0),
            classRow("G5 RHINO", "Ms. Faith Muthoni", listOf(90.0, 70.0, 75.0, 85.0, 70.0), 1, 390.0, 1, if (studentCounts) 20 else 0),
            classRow("G5 CHEETAH", "Mrs. Alice Njeri", listOf(40.0, 30.0, 35.0, 45.0, 30.0), 5, 180.0, 5, if (studentCounts) 10 else 0),
        )
        val contributions = subjectsA.mapIndexed { index, subject ->
            SubjectTeacherContributionDto(
                subjectId = "s" + index,
                subjectName = subject,
                gradeMean = 0.0,
                teachers = listOf(
                    TeacherContributionDto(
                        teacherUserId = "u1",
                        teacherName = "J. Mwangi",
                        classTags = listOf("G5 LION"),
                        learners = 10,
                        mean = rows.first().subjectAverages.getValue(subject),
                    ),
                ),
            )
        }
        return TraditionalGradeAnalysisDto(
            examId = "exam-a",
            gradeLevel = "Grade 5",
            term = "Term 3",
            year = 2026,
            classes = rows,
            subjects = subjectsA,
            subjectTeacherContributions = contributions,
        )
    }

    private fun classRow(
        tag: String,
        teacher: String,
        means: List<Double>,
        position: Int,
        total: Double,
        overallPosition: Int,
        studentCount: Int,
        subjects: List<String> = subjectsA,
    ): ClassGradeAnalysisDto = ClassGradeAnalysisDto(
        classTag = tag,
        teacherName = teacher,
        subjectAverages = subjects.zip(means).toMap(),
        subjectPositions = subjects.associateWith { position },
        totalAverage = total,
        overallPosition = overallPosition,
        studentCount = studentCount,
    )

    @Test
    fun `page one renders the class comparison, learner-weighted grade mean and callouts`() {
        val spec = spec(fixtureA())
        val bytes = renderer.render(spec)
        val pages = textByPage(bytes)

        assertEquals(2, pages.size, "page 1 (table) + 1 chart page")
        pageSizes(bytes).forEach { (width, height) ->
            assertEquals(GradeAnalysisRenderer.PAGE_WIDTH, width, 0.01f)
            assertEquals(GradeAnalysisRenderer.PAGE_HEIGHT, height, 0.01f)
        }

        val page = flatten(pages[0])
        assertTrue(page.contains("GRADE5ANALYSIS-TERM32026"), page)
        // "positions" extracts as "posions" until A14 fixes the font ligature (see class doc).
        assertTrue(page.contains("Classcomparison-subjectmeans"), page)
        assertTrue(page.contains("CLASSTEACHER"), page)
        assertTrue(page.contains("GRADEMEAN"), page)

        // Rows are sorted by overall position, not by the payload order.
        val order = listOf("G5RHINO", "G5LION", "G5BUFFALO", "G5ELEPHANT", "G5CHEETAH").map { page.indexOf(it) }
        assertTrue(order.all { it >= 0 }, "every class must be printed: " + order)
        assertEquals(order.sorted(), order, "rows must follow overallPosition")

        // Teacher names and every class tag. Names containing "ti" ("Otieno", "Muthoni") are
        // not asserted here because of the A14 ligature extraction defect documented above.
        assertTrue(page.contains("Mr.PeterKamau"), page)
        assertTrue(page.contains("Ms.GraceWairimu"), page)

        // Learner-weighted grade mean row: Σ(mean × studentCount) ÷ Σ studentCount.
        val analysis = fixtureA()
        assertEquals(5700.0 / 90.0, GradeAnalysisRenderer.weightedSubjectMeans(analysis)[0]!!, 1e-9)
        assertEquals(284.0 + 4.0 / 9.0, GradeAnalysisRenderer.weightedOverallMean(analysis)!!, 1e-9)
        assertTrue(page.contains("63.3%"), "weighted Mathematics grade mean missing: " + page)
        assertTrue(page.contains("55.6%"), "weighted Kiswahili grade mean missing: " + page)
        assertTrue(page.contains("284.4"), "weighted overall grade mean missing: " + page)
        assertFalse(page.contains("286.0"), "unweighted overall mean must not be printed when class sizes exist")

        // Callouts: strongest subject is Integrated Science (65.0 %) and the widest
        // class gap is Mathematics (40.0 % - 90.0 %).
        assertTrue(page.contains("STRONGESTSUBJECT"), page)
        assertTrue(page.contains("WIDESTCLASSGAP"), page)
        assertTrue(page.contains("IntegratedScience"), page)
        assertTrue(page.contains("65.0%"), page)
        assertTrue(page.contains("40.0%-90.0%"), page)

        // Default platform bands when the session snapshot is absent (D18).
        assertTrue(page.contains("80-100"), page)
        assertTrue(page.contains("Page1of2"), page)

        // Page 2 carries the charts, the required footnote and the last-page numbering.
        val chartPage = flatten(pages[1])
        // "CONTRIBUTION" extracts as "CONTRIBUON" until A14 fixes the ligature.
        assertTrue(chartPage.contains("SUBJECTTEACHER"), chartPage)
        assertTrue(chartPage.contains("-GRADE5-TERM32026"), chartPage)
        assertTrue(chartPage.contains("currentclass/subjectassignments"), chartPage)
        assertTrue(chartPage.contains("grademean"), chartPage)
        assertTrue(chartPage.contains("Page2of2"), chartPage)
    }

    @Test
    fun `grade mean falls back to an unweighted mean and says so when no class size exists`() {
        val page = flatten(textByPage(renderer.render(spec(fixtureA(studentCounts = false))))[0])

        assertTrue(page.contains("unweighted(noclasssizes)"), page)
        // Unweighted Mathematics mean is 64.0, the weighted one would be 63.3.
        assertTrue(page.contains("64.0%"), page)
        assertTrue(page.contains("286.0"), page)
        assertFalse(page.contains("63.3%"), "the weighted mean must not be used when every studentCount is 0")
    }

    // --------------------------------------------------------------- fixture B

    private val subjectsB = listOf(
        "Mathematics",
        "English",
        "Kiswahili",
        "Integrated Science",
        "Social Studies",
        "CRE",
        "Agriculture",
        "Creative Arts",
        "Physical & Health Education",
        "Pre-Technical Studies",
        "Business Studies",
        "Home Science",
        "Computer Science",
    )
    private val classesB = listOf("G5 LION", "G5 BUFFALO", "G5 ELEPHANT", "G5 RHINO", "G5 CHEETAH")
    private val teachersB = listOf("Mr. Otieno", "Ms. Wairimu", "Mr. Kamau", "Ms. Muthoni", "Mrs. Njeri")

    private fun fixtureB(): TraditionalGradeAnalysisDto {
        val rows = classesB.mapIndexed { index, tag ->
            val means = subjectsB.take(12).map { subject -> subject to classMean(subject, index) }
            ClassGradeAnalysisDto(
                classTag = tag,
                teacherName = teachersB[index],
                subjectAverages = means.toMap(),
                subjectPositions = subjectsB.take(12).associateWith { 5 - index },
                totalAverage = means.sumOf { it.second },
                overallPosition = 5 - index,
                studentCount = 20,
            )
        }
        val contributions = subjectsB.mapIndexed { index, subject ->
            if (index == 12) {
                SubjectTeacherContributionDto(subjectId = "s$index", subjectName = subject)
            } else if (index == 2) {
                // Five Kiswahili teachers: one per class, a co-taught duplicate, a former
                // teacher account and one unattributed class (L6 reference fixture).
                SubjectTeacherContributionDto(
                    subjectId = "s$index",
                    subjectName = subject,
                    gradeMean = classMean(subject, 0) + 2.0,
                    teachers = listOf(
                        TeacherContributionDto("u0", "J. Mwangi", listOf("G5 LION"), 20, classMean(subject, 0)),
                        TeacherContributionDto("u1", "A. Achieng", listOf("G5 BUFFALO"), 20, classMean(subject, 1)),
                        TeacherContributionDto("u2", "P. Kariuki", listOf("G5 ELEPHANT"), 20, classMean(subject, 2), former = true),
                        TeacherContributionDto("u3", "M. Njeri", listOf("G5 RHINO"), 20, classMean(subject, 3)),
                        TeacherContributionDto("u0", "J. Mwangi", listOf("G5 LION"), 20, classMean(subject, 0), shared = true),
                    ),
                    unassigned = UnassignedContributionDto(learners = 20, mean = classMean(subject, 4)),
                )
            } else {
                SubjectTeacherContributionDto(
                    subjectId = "s$index",
                    subjectName = subject,
                    gradeMean = classMean(subject, 0) + 2.0,
                    teachers = classesB.mapIndexed { classIndex, tag ->
                        TeacherContributionDto(
                            teacherUserId = "u$classIndex",
                            teacherName = teachersB[classIndex],
                            classTags = listOf(tag),
                            learners = 20,
                            mean = classMean(subject, classIndex),
                        )
                    },
                )
            }
        }
        return TraditionalGradeAnalysisDto(
            examId = "exam-b",
            gradeLevel = "Grade 5",
            term = "Term 3",
            year = 2026,
            classes = rows,
            subjects = subjectsB,
            subjectTeacherContributions = contributions,
        )
    }

    /** Class mean for a 13-subject exam fixture: identical across classes for a subject's grade mean. */
    private fun classMean(subject: String, classIndex: Int): Double =
        40.0 + 2.0 * subjectsB.indexOf(subject) + 5.0 * classIndex

    @Test
    fun `chart pages print six subject panels each with every teacher mark`() {
        val bytes = renderer.render(spec(fixtureB()))
        val pages = textByPage(bytes)

        // 13 subjects = 3 table pages (6 columns each) + 3 chart pages (6 panels each).
        assertEquals(6, pages.size)
        pages.forEach { page ->
            assertFalse(flatten(page).isEmpty(), "every page must have content")
        }
        assertEquals(3, pages.count { flatten(it).contains("CLASSTEACHER") }, "one header per table page")

        val firstChartPage = flatten(pages[3])
        assertEquals(6, firstChartPage.split("grademean").size - 1, "six panels on a chart page")
        assertTrue(firstChartPage.contains("J.Mwangi"), firstChartPage)
        assertTrue(firstChartPage.contains("Formerteacher"), firstChartPage)
        assertTrue(firstChartPage.contains("Unassigned"), firstChartPage)
        assertTrue(firstChartPage.contains("(shared)"), firstChartPage)
        assertTrue(firstChartPage.contains("G5LION"), firstChartPage)
        assertTrue(firstChartPage.contains("Teachermeansusecompletemarks"), firstChartPage)

        assertEquals(6, flatten(pages[4]).split("grademean").size - 1)
        val lastChartPage = flatten(pages[5])
        assertTrue(lastChartPage.contains("Nocomplete"), lastChartPage)
        assertEquals(0, lastChartPage.split("grademean").size - 1, "the last subject has no complete marks")
        assertTrue(lastChartPage.contains("Teachermeansusecompletemarks"), lastChartPage)
    }

    @Test
    fun `chart bar means reconcile with the page one subject mean`() {
        val analysis = fixtureB()
        val contribution = analysis.subjectTeacherContributions.first()
        val pageMean = GradeAnalysisRenderer.weightedSubjectMeans(analysis, listOf(contribution.subjectName)).single()!!
        val learnerCount = contribution.teachers.sumOf { it.learners }
        val weightedBars = contribution.teachers.sumOf { it.mean * it.learners } / learnerCount

        assertEquals(50.0, pageMean, 1e-9)
        assertEquals(pageMean, weightedBars, 1e-9, "bars must account for the page-1 subject mean")

        val chartPage = flatten(textByPage(renderer.render(spec(analysis)))[3])
        assertTrue(chartPage.contains("50.0%"), "the panel caption prints the same grade mean: " + chartPage)
    }

    // --------------------------------------------------------------- fixture C

    @Test
    fun `more than sixteen teachers continue the panel on the next one`() {
        val analysis = TraditionalGradeAnalysisDto(
            examId = "exam-c",
            gradeLevel = "Grade 4",
            term = "Term 1",
            year = 2026,
            classes = listOf(
                ClassGradeAnalysisDto("4A", "Mr. One", mapOf("Mathematics" to 60.0), mapOf("Mathematics" to 2), 300.0, 2, 10),
                ClassGradeAnalysisDto("4B", "Ms. Two", mapOf("Mathematics" to 70.0), mapOf("Mathematics" to 1), 350.0, 1, 10),
            ),
            subjects = listOf("Mathematics"),
            subjectTeacherContributions = listOf(
                SubjectTeacherContributionDto(
                    subjectId = "s0",
                    subjectName = "Mathematics",
                    gradeMean = 65.0,
                    teachers = (1..18).map { index ->
                        TeacherContributionDto("u$index", "Teacher " + index.toString().padStart(2, '0'), listOf("4A"), 5, 40.0 + index)
                    },
                ),
            ),
        )
        val pages = textByPage(renderer.render(spec(analysis)))

        assertEquals(2, pages.size)
        val chartPage = flatten(pages[1])
        assertTrue(chartPage.contains("MATHEMATICS(CONTINUED)"), chartPage)
        assertTrue(chartPage.contains("Teacher18"), chartPage)
        assertTrue(chartPage.contains("Teacher09"), chartPage)
        assertTrue(chartPage.contains("Teacher01"), chartPage)
    }

    // --------------------------------------------------------------- fixture D

    @Test
    fun `class rows paginate with a repeated header and the legend follows the session bands`() {
        val tags = (0 until 12).map { "G6 " + ('A' + it) }
        val subjects = listOf("Mathematics", "English", "Kiswahili", "Science", "Social Studies")
        val rows = tags.mapIndexed { index, tag ->
            val means = subjects.mapIndexed { subjectIndex, _ -> 20.0 + index * 4.0 + subjectIndex * 2.0 }
            ClassGradeAnalysisDto(
                classTag = tag,
                teacherName = "Teacher " + (index + 1),
                subjectAverages = subjects.zip(means).toMap(),
                subjectPositions = subjects.associateWith { 12 - index },
                totalAverage = means.sum(),
                overallPosition = 12 - index,
                studentCount = 0,
            )
        }
        val customBands = GradingConfigDto(
            bands = listOf(
                GradingBandDto("A", 75),
                GradingBandDto("B", 50),
                GradingBandDto("C", 0),
            ),
        )
        val analysis = TraditionalGradeAnalysisDto(
            examId = "exam-d",
            gradeLevel = "Grade 6",
            term = "Term 2",
            year = 2026,
            classes = rows,
            subjects = subjects,
            subjectTeacherContributions = subjects.mapIndexed { index, subject ->
                SubjectTeacherContributionDto(
                    subjectId = "s$index",
                    subjectName = subject,
                    gradeMean = 40.0,
                    teachers = listOf(TeacherContributionDto("u$index", "Teacher " + (index + 1), listOf(tags.first()), 12, 40.0)),
                )
            },
            grading = customBands,
        )
        val pages = textByPage(renderer.render(spec(analysis)))
        val firstTablePage = flatten(pages[0])
        val secondTablePage = flatten(pages[1])

        // 12 classes = 2 table pages (10 + 2 rows) + 1 chart page.
        assertEquals(3, pages.size)
        assertEquals(2, pages.count { flatten(it).contains("CLASSTEACHER") })
        // Rows are sorted by overallPosition, so class L (1st) .. class C (10th) fill page 1
        // and classes B/A continue on page 2 under a repeated header.
        assertTrue(firstTablePage.contains("G6L"), firstTablePage)
        assertTrue(firstTablePage.contains("G6C"), firstTablePage)
        assertFalse(firstTablePage.contains("G6B"), "class B (position 11) belongs to the second page")
        assertTrue(secondTablePage.contains("G6B"), secondTablePage)
        assertTrue(secondTablePage.contains("G6A"), secondTablePage)

        // Callouts only on the final table page.
        assertFalse(firstTablePage.contains("STRONGESTSUBJECT"), firstTablePage)
        assertTrue(secondTablePage.contains("STRONGESTSUBJECT"), secondTablePage)

        // Session bands replace the platform defaults in the legend (D18), unweighted fallback.
        assertTrue(secondTablePage.contains("75-100"), secondTablePage)
        assertTrue(secondTablePage.contains("50-74"), secondTablePage)
        assertTrue(secondTablePage.contains("0-49"), secondTablePage)
        assertFalse(secondTablePage.contains("80-100"), "platform bands must not be drawn when a snapshot exists")
        assertTrue(secondTablePage.contains("unweighted(noclasssizes)"), secondTablePage)
    }

    // ---------------------------------------------------------------- helpers

    private fun spec(analysis: TraditionalGradeAnalysisDto): GradeAnalysisSpec = GradeAnalysisSpec(
        title = analysis.gradeLevel + " Grade Analysis",
        branding = ReportBranding(schoolName = "Milimani Primary School", motto = "Nurture - Discover - Excel"),
        analysis = analysis,
    )

    private fun pageSizes(bytes: ByteArray): List<Pair<Float, Float>> = Loader.loadPDF(bytes).use { document ->
        (0 until document.numberOfPages).map { index ->
            val box = document.getPage(index).mediaBox
            box.width to box.height
        }
    }

    private fun textByPage(bytes: ByteArray): List<String> = Loader.loadPDF(bytes).use { document ->
        (0 until document.numberOfPages).map { index ->
            PDFTextStripper().apply {
                startPage = index + 1
                endPage = index + 1
            }.getText(document)
        }
    }

    /** Removes whitespace so tracked (letter-spaced) labels assert the same as plain text. */
    private fun flatten(value: String): String = value.filterNot { it.isWhitespace() }
}
