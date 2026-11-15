package com.afrithecus.brainbox.api.report.render

import com.afrithecus.brainbox.api.report.GradeTableSpec
import com.afrithecus.brainbox.api.report.ReportBranding
import com.afrithecus.brainbox.api.report.ReportGradingBand
import com.afrithecus.brainbox.api.traditional.model.ExamTerm
import com.afrithecus.brainbox.api.traditional.model.TraditionalExamStatus
import com.afrithecus.brainbox.api.traditional.web.StudentGradeRowDto
import com.afrithecus.brainbox.api.traditional.web.SubjectConfigDto
import com.afrithecus.brainbox.api.traditional.web.TraditionalExamDto
import com.afrithecus.brainbox.api.traditional.web.TraditionalSubjectResultDto
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.text.PDFTextStripper
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for the two dense traditional list renderers (A9a/A9b) and their shared model.
 *
 * Fixtures mirror the approved lab (template/proto/src/lab/LabData.java): 500 learners across five
 * Grade 5 classes, 100 per class, of which 15 are incomplete (three per class: one missing subject
 * and two whole-subject zeros), plus deliberate ties and accented/long names. Subjects come from
 * the decision-13 abbreviation catalogue at S = 5 and S = 13 so the portrait and landscape compact
 * geometries are both exercised.
 */
class TraditionalListRendererTest {

    private val combinedRenderer = TraditionalCombinedRenderer()
    private val perClassRenderer = TraditionalPerClassRenderer()

    // --------------------------------------------------------------- fixtures

    private companion object {
        const val CLASS_SIZE = 100
        const val INCOMPLETE_PER_CLASS = 3
        const val LEARNERS = CLASS_SIZE * 5

        val CLASS_TAGS = listOf(
            "Grade 5 Lion",
            "Grade 5 Buffalo",
            "Grade 5 Elephant",
            "Grade 5 Rhino",
            "Grade 5 Cheetah",
        )

        val CLASS_TEACHERS: Map<String, String> = linkedMapOf(
            "Grade 5 Lion" to "Mr. James Otieno",
            "Grade 5 Buffalo" to "Ms. Grace Wairimu",
            "Grade 5 Elephant" to "Mr. Peter Kamau",
            "Grade 5 Rhino" to "Ms. Faith Muthoni",
            "Grade 5 Cheetah" to "Mrs. Alice Njeri",
        )

        /** name to max score, in the decision-13 catalogue order. */
        val SUBJECTS: List<Pair<String, Int>> = listOf(
            "Mathematics" to 100,
            "English" to 40,
            "Kiswahili" to 40,
            "Integrated Science" to 50,
            "Social Studies" to 50,
            "CRE" to 30,
            "Agriculture" to 50,
            "Creative Arts" to 50,
            "Physical & Health Education" to 100,
            "Pre-Technical Studies" to 100,
            "Business Studies" to 100,
            "Home Science" to 100,
            "Computer Science" to 100,
        )

        val FIRST = listOf(
            "Amina", "Brian", "Cynthia", "David", "Esther", "Faith", "George", "Hawa", "Ian",
            "Joy", "Kelvin", "Lucy", "Moses", "Nancy", "Oscar", "Purity", "Quinter", "Ronald",
            "Sharon", "Timothy", "Uzima", "Victor", "Wanjiku", "Xavier", "Yusuf", "Zawadi",
        )

        val LAST = listOf(
            "Mwangi", "Ochieng", "Wanjiku", "Otieno", "Kariuki", "Achieng", "Njeri", "Kamau",
            "Mutua", "Omollo", "Waweru", "Chebet", "Kiptoo", "Onyango", "Muthoni", "Gitau",
        )

        fun level(percentage: Double, bands: List<ReportGradingBand>): String =
            bands.firstOrNull { percentage >= it.minPercentage }?.label ?: bands.last().label

        fun learnerName(sequence: Int): String = when (sequence) {
            7 -> "Ngũgĩ wa Thiong'o"
            9 -> "Wanjiku Wambui Njoroge Kipchoge Muthoni"
            11 -> "Amina Mwangi"
            else -> FIRST[(sequence * 13) % FIRST.size] + " " + LAST[(sequence * 29) % LAST.size]
        }

        fun exam(subjectCount: Int): TraditionalExamDto {
            val subjects = SUBJECTS.take(subjectCount).mapIndexed { index, (name, maxScore) ->
                SubjectConfigDto(subjectId = "subject-" + (index + 1), name = name, maxScore = maxScore)
            }
            return TraditionalExamDto(
                examId = "exam-grade-5-term-2",
                title = "End Term 2 2026 - Grade 5",
                term = ExamTerm.TERM_2,
                gradeLevel = "Grade 5",
                year = 2026,
                status = TraditionalExamStatus.PUBLISHED,
                subjects = subjects,
                createdAt = 1_760_000_000_000L,
            )
        }

        fun rows(subjectCount: Int): List<StudentGradeRowDto> {
            val exam = exam(subjectCount)
            val bands = TraditionalReportModel.DEFAULT_BANDS
            val out = ArrayList<StudentGradeRowDto>(LEARNERS)
            var sequence = 0
            CLASS_TAGS.forEachIndexed { classIndex, classTag ->
                repeat(CLASS_SIZE) { learnerIndex ->
                    sequence++
                    val incompleteKind = if (learnerIndex < INCOMPLETE_PER_CLASS) {
                        (classIndex + learnerIndex) % 3
                    } else {
                        -1
                    }
                    val missingSubject = if (incompleteKind == 0) {
                        (classIndex + learnerIndex) % subjectCount
                    } else {
                        -1
                    }
                    val zeroSubject = if (incompleteKind == 1 || incompleteKind == 2) {
                        (classIndex + learnerIndex + 1) % subjectCount
                    } else {
                        -1
                    }
                    // Learners whose sequence is a multiple of 41 all score exactly 60 % per subject,
                    // which gives the ranking deterministic ties for the name tie-break test.
                    val ability = if (sequence % 41 == 0) 60 else 48 + (sequence * 37) % 40
                    val results = ArrayList<TraditionalSubjectResultDto>(subjectCount)
                    exam.subjects.forEachIndexed { subjectIndex, subject ->
                        if (subjectIndex == missingSubject) return@forEachIndexed
                        val max = subject.maxScore
                        val raw = if (subjectIndex == zeroSubject) {
                            0
                        } else {
                            (ability * max / 100.0).roundToInt().coerceIn(0, max)
                        }
                        val percentage = raw.toDouble() / max * 100.0
                        results += TraditionalSubjectResultDto(
                            subjectName = subject.name,
                            rawScore = raw,
                            maxScore = max,
                            percentage = percentage,
                            grade = level(percentage, bands),
                        )
                    }
                    val total = results.sumOf { it.percentage }
                    out += StudentGradeRowDto(
                        rank = sequence,
                        studentId = "learner-" + sequence,
                        studentName = learnerName(sequence),
                        classTag = classTag,
                        subjects = results,
                        totalPercentage = total,
                        totalScore = 0,
                        overallGrade = level(total / subjectCount, bands),
                    )
                }
            }
            return out
        }

        fun spec(
            subjectCount: Int,
            bands: List<ReportGradingBand> = emptyList(),
            blankOverallGrades: Boolean = false,
        ): GradeTableSpec {
            val baseRows = rows(subjectCount)
            return GradeTableSpec(
                title = "End Term 2 2026 - Grade 5",
                branding = ReportBranding(
                    schoolName = "Milimani Primary School",
                    motto = "Nurture - Discover - Excel",
                    watermarkText = "WATERMARK-SENTINEL",
                ),
                exam = exam(subjectCount),
                rows = if (blankOverallGrades) baseRows.map { it.copy(overallGrade = "") } else baseRows,
                bands = bands,
                classTeachers = CLASS_TEACHERS,
            )
        }

        fun completeRows(subjectCount: Int): List<StudentGradeRowDto> = rows(subjectCount).filter { row ->
            row.subjects.size == subjectCount && row.subjects.all { it.rawScore > 0 }
        }
    }

    // --------------------------------------------------------------- helpers

    private fun PDDocument.pageText(pageIndex: Int): String {
        val stripper = PDFTextStripper()
        stripper.startPage = pageIndex + 1
        stripper.endPage = pageIndex + 1
        return stripper.getText(this)
    }

    private fun PDDocument.fullText(): String = PDFTextStripper().getText(this)

    private fun PDDocument.assertPortrait() {
        for (index in 0 until numberOfPages) {
            val page = getPage(index)
            assertEquals(595.28f, page.mediaBox.width, 0.05f, "page " + (index + 1) + " width")
            assertEquals(841.89f, page.mediaBox.height, 0.05f, "page " + (index + 1) + " height")
        }
    }

    private fun PDDocument.assertLandscape() {
        for (index in 0 until numberOfPages) {
            val page = getPage(index)
            assertEquals(841.89f, page.mediaBox.width, 0.05f, "page " + (index + 1) + " width")
            assertEquals(595.28f, page.mediaBox.height, 0.05f, "page " + (index + 1) + " height")
        }
    }

    // --------------------------------------------------------------- combined

    @Test
    fun combinedFiveSubjectsIsPortraitWithTheApprovedGeometry() {
        val spec = spec(5)
        val model = TraditionalReportModel.combined(spec)
        assertEquals(5, model.subjects.size)
        assertEquals(485, model.rankedCount)
        assertEquals(15, model.incompleteCount)
        assertEquals(listOf("MATH", "ENG", "KISW", "INT", "SST"), model.subjects.map { it.abbreviation })

        val layout = ReportTableLayout(5)
        val expectedPages = layout.plan(model.rankedCount).size + layout.incompletePages(model.incompleteCount)
        Loader.loadPDF(combinedRenderer.render(spec)).use { document ->
            assertEquals(13, document.numberOfPages)
            assertEquals(expectedPages, document.numberOfPages)
            document.assertPortrait()
            assertTrue(document.pageText(0).contains("MATH"))
            assertTrue(document.fullText().contains("GRADE 5 COMBINED RESULTS"))
        }
    }

    @Test
    fun combinedThirteenSubjectsIsLandscapeCompact() {
        val spec = spec(13)
        val model = TraditionalReportModel.combined(spec)
        assertEquals(13, model.subjects.size)
        assertEquals(485, model.rankedCount)
        assertEquals(15, model.incompleteCount)
        assertEquals("PRETECH", model.subjects[9].abbreviation)

        val layout = ReportTableLayout(13)
        assertTrue(layout.compact)
        val expectedPages = layout.plan(model.rankedCount).size + layout.incompletePages(model.incompleteCount)
        Loader.loadPDF(combinedRenderer.render(spec)).use { document ->
            assertEquals(15, document.numberOfPages)
            assertEquals(expectedPages, document.numberOfPages)
            document.assertLandscape()
            assertTrue(document.pageText(0).contains("KISW"))
        }
    }

    @Test
    fun combinedHeaderRepeatsOnEveryPage() {
        Loader.loadPDF(combinedRenderer.render(spec(13))).use { document ->
            for (index in 0 until document.numberOfPages) {
                val text = document.pageText(index)
                assertTrue(text.contains("MATH"), "header abbreviation missing on page " + (index + 1))
            }
        }
    }

    @Test
    fun combinedHasNoClassColumn() {
        Loader.loadPDF(combinedRenderer.render(spec(5))).use { document ->
            val text = document.fullText()
            for (tag in CLASS_TAGS) {
                assertFalse(text.contains(tag), "combined document leaked class tag " + tag)
            }
        }
    }

    @Test
    fun modelTotalsFollowTheWorkbookFormulas() {
        val spec = spec(13)
        val model = TraditionalReportModel.combined(spec)
        val dto = spec.rows.first { it.studentId == "learner-11" }
        val row = model.ranked.first { it.studentId == "learner-11" }

        assertEquals(13, row.cells.size)
        assertEquals(dto.totalPercentage, row.total, 1e-9)
        assertEquals(row.cells.sumOf { it.percentage }, row.total, 1e-9)
        assertEquals(row.total / 13.0, row.mean, 1e-9)

        row.cells.forEachIndexed { index, cell ->
            val max = spec.exam.subjects[index].maxScore
            assertTrue(cell.present)
            assertTrue(cell.rawScore > 0)
            assertEquals(cell.rawScore.toDouble() / max * 100.0, cell.percentage, 1e-9)
        }

        assertEquals(model.ranked.sumOf { it.total }, model.learnerTotalSum, 1e-9)
        assertEquals(model.ranked.sumOf { it.mean } / model.rankedCount, model.overallMean, 1e-9)
        model.subjects.indices.forEach { index ->
            assertEquals(model.ranked.sumOf { it.cells[index].percentage } / model.rankedCount, model.subjectMeans[index], 1e-9)
        }
        assertEquals(TraditionalReportModel.levelFor(model.overallMean, model.bands), model.overallGrade)
    }

    @Test
    fun incompleteLearnersAreNeverRankedAndKeepTheirOwnTable() {
        val spec = spec(5)
        val model = TraditionalReportModel.combined(spec)
        assertEquals(LEARNERS - 15, model.rankedCount)
        assertEquals(15, model.incompleteCount)
        assertTrue(model.ranked.all { it.position != null })
        assertTrue(model.ranked.all { row -> row.cells.all { it.present && it.rawScore > 0 } })
        assertTrue(model.incomplete.all { it.position == null })
        assertEquals((1..model.rankedCount).toList(), model.ranked.map { it.position })
        for (index in 1 until model.ranked.size) {
            assertTrue(model.ranked[index - 1].total >= model.ranked[index].total)
        }
        val rankedIds = model.ranked.map { it.studentId }.toSet()
        assertTrue(model.incomplete.none { it.studentId in rankedIds })

        Loader.loadPDF(combinedRenderer.render(spec)).use { document ->
            val text = document.fullText().replace(Regex("\\s+"), " ")
            assertTrue(text.contains("STUDENTS WITH INCOMPLETE MARKS"))
            // The embedded-Lato 'ti' ligature has no ToUnicode mapping yet (register F20/A14), so the
            // note is asserted through phrases that do not contain it.
            assertTrue(text.contains("At least one subject has no mark"))
            assertTrue(text.contains("request a mark"))
            assertTrue(text.contains("from your coordinator"))
            assertTrue(text.contains("genuine zero must be corrected to 1"))
            model.incomplete.take(3).forEach { assertTrue(text.contains(it.studentName), "missing incomplete row " + it.studentName) }
        }
    }

    @Test
    fun tiesBreakByNameAscendingWithUniqueSequentialPositions() {
        val model = TraditionalReportModel.combined(spec(5))
        val tieGroups = model.ranked.groupBy { it.total }.filterValues { it.size > 1 }
        assertTrue(tieGroups.isNotEmpty(), "fixture should contain total ties")
        val comparator = compareBy<String> { it.lowercase() }.thenBy { it }
        tieGroups.values.forEach { rows ->
            assertEquals(rows.map { it.studentName }.sortedWith(comparator), rows.map { it.studentName })
        }
        assertEquals(model.ranked.size, model.ranked.mapNotNull { it.position }.distinct().size)
    }

    @Test
    fun zeroComponentInsideACombinedSubjectDoesNotHideTheLearner() {
        val exam = TraditionalExamDto(
            examId = "combined-exam",
            title = "Combined Subject Exam",
            term = ExamTerm.TERM_2,
            gradeLevel = "Grade 5",
            year = 2026,
            status = TraditionalExamStatus.PUBLISHED,
            subjects = listOf(
                SubjectConfigDto("math", "Mathematics", 100),
                SubjectConfigDto("eng", "English", 40),
            ),
            createdAt = 1_760_000_000_000L,
        )
        val rows = listOf(
            StudentGradeRowDto(
                rank = 1, studentId = "component-zero", studentName = "Amina Mwangi", classTag = "Grade 5 Lion",
                subjects = listOf(
                    TraditionalSubjectResultDto("Mathematics", 80, 100, 80.0, "EE"),
                    TraditionalSubjectResultDto("English", 20, 40, 50.0, "AE"),
                ),
                totalPercentage = 130.0, totalScore = 0, overallGrade = "ME",
            ),
            StudentGradeRowDto(
                rank = 2, studentId = "subject-zero", studentName = "Brian Otieno", classTag = "Grade 5 Lion",
                subjects = listOf(
                    TraditionalSubjectResultDto("Mathematics", 70, 100, 70.0, "ME"),
                    TraditionalSubjectResultDto("English", 0, 40, 0.0, "E"),
                ),
                totalPercentage = 70.0, totalScore = 0, overallGrade = "ME",
            ),
        )
        val model = TraditionalReportModel.combined(
            GradeTableSpec("Combined", ReportBranding("Milimani Primary School"), exam, rows),
        )
        assertEquals(listOf("component-zero"), model.ranked.map { it.studentId })
        assertEquals(listOf("subject-zero"), model.incomplete.map { it.studentId })
    }

    @Test
    fun sessionBandsDriveLevelsAndTheTotalsLegend() {
        val custom = listOf(
            ReportGradingBand("EE", 75.0),
            ReportGradingBand("ME", 60.0),
            ReportGradingBand("AE", 45.0),
            ReportGradingBand("BE", 30.0),
            ReportGradingBand("DE", 15.0),
            ReportGradingBand("E", 0.0),
        )
        val spec = spec(5, bands = custom, blankOverallGrades = true)
        val model = TraditionalReportModel.combined(spec)
        assertEquals(custom, model.bands)
        model.ranked.forEach { row ->
            assertEquals(level(row.mean, custom), row.level)
        }
        // A learner between 60 and 65 is ME on the saved session bands but AE on the platform defaults.
        val borderline = model.ranked.first { it.mean in 60.0..64.999 }
        assertEquals("ME", borderline.level)
        assertEquals("AE", level(borderline.mean, TraditionalReportModel.DEFAULT_BANDS))

        Loader.loadPDF(combinedRenderer.render(spec)).use { document ->
            val totalsPage = (0 until document.numberOfPages)
                .first { document.pageText(it).contains("OVERALL") }
            val text = document.pageText(totalsPage)
            assertTrue(text.contains("75-100"), "custom band legend missing: " + text)
        }
    }

    @Test
    fun totalsBlockCarriesTheExamOfficeStampAndTheFormulaNote() {
        val spec = spec(5)
        val completeOnly = spec.copy(rows = completeRows(5))
        Loader.loadPDF(combinedRenderer.render(completeOnly)).use { document ->
            val lastPage = document.pageText(document.numberOfPages - 1)
            assertTrue(lastPage.contains("OVERALL"))
            assertTrue(lastPage.contains("Official record"))
            assertTrue(lastPage.contains("incomplete marks excluded"))
        }
    }

    @Test
    fun textIsExtractableIncludingAccentedNamesAndPageNumbers() {
        Loader.loadPDF(combinedRenderer.render(spec(5))).use { document ->
            val text = document.fullText()
            assertTrue(text.contains("Amina Mwangi"), "plain name not extractable")
            assertTrue(text.contains("Ngũgĩ"), "accented name not extractable")
            assertTrue(text.contains("Page 1 of 13"))
            assertTrue(text.contains("Page 13 of 13"))
        }
    }

    @Test
    fun abbreviationsUseTheFixedMapAndFallBackToTheFirstFourLetters() {
        assertEquals("MATH", TraditionalSubjectAbbreviations.of("Mathematics"))
        assertEquals("INT", TraditionalSubjectAbbreviations.of("Integrated Science"))
        assertEquals("SST", TraditionalSubjectAbbreviations.of("Social Studies"))
        assertEquals("CRE", TraditionalSubjectAbbreviations.of("CRE"))
        assertEquals("PRETECH", TraditionalSubjectAbbreviations.of("Pre-Technical Studies"))
        assertEquals("PHE", TraditionalSubjectAbbreviations.of("Physical & Health Education"))
        assertEquals("GER", TraditionalSubjectAbbreviations.of("German"))
        assertEquals("ENVI", TraditionalSubjectAbbreviations.of("Environmental Activities"))
        assertEquals("SUBJ", TraditionalSubjectAbbreviations.of(" "))
    }

    // --------------------------------------------------------------- per class

    @Test
    fun perClassSinglePdfContainsOnlyThatClass() {
        val spec = spec(5)
        val lion = TraditionalReportModel.perClass(spec, "Grade 5 Lion")
        assertEquals(97, lion.rankedCount)
        assertEquals(3, lion.incompleteCount)
        assertEquals("Mr. James Otieno", lion.classTeacher)
        assertNotNull(lion.classMean)

        Loader.loadPDF(perClassRenderer.render(spec, "Grade 5 Lion")).use { document ->
            assertEquals(4, document.numberOfPages)
            document.assertPortrait()
            val text = document.fullText().replace(Regex("\\s+"), " ")
            assertTrue(text.contains("GRADE 5 LION"))
            // 'Otieno' extracts as 'O eno' until the F20/A14 ligature fix lands; the class teacher is
            // asserted on the name prefix plus the header clause.
            assertTrue(text.contains("Class teacher: Mr. James"), text)
            assertTrue(text.contains("97 ranked"))
            assertTrue(text.contains("3 incomplete"))
            assertTrue(text.contains("STUDENTS WITH INCOMPLETE MARKS"))
            lion.incomplete.forEach { row -> assertTrue(text.contains(row.studentName), "own incomplete row missing " + row.studentName) }
            for (tag in CLASS_TAGS.filter { it != "Grade 5 Lion" }) {
                val short = tag.substringAfterLast(' ')
                assertFalse(text.contains(short), "single-class PDF leaked class " + short)
            }
        }
    }

    @Test
    fun perClassWithinClassPositionsAreReRanked() {
        val spec = spec(13)
        val buffalo = TraditionalReportModel.perClass(spec, "Grade 5 Buffalo")
        assertEquals(97, buffalo.rankedCount)
        assertEquals((1..97).toList(), buffalo.ranked.map { it.position })
        for (index in 1 until buffalo.ranked.size) {
            assertTrue(buffalo.ranked[index - 1].total >= buffalo.ranked[index].total)
        }
        // The service still hands back grade-wide ranks (F5 waiting on D11); the renderer re-ranks.
        assertTrue(
            buffalo.ranked.any { row ->
                val gradeRank = spec.rows.first { it.studentId == row.studentId }.rank
                gradeRank != row.position
            },
            "within-class positions should differ from the grade-wide server ranks",
        )
        assertEquals(buffalo.ranked.maxOf { it.total }, buffalo.ranked.first().total)

        Loader.loadPDF(perClassRenderer.render(spec, "Grade 5 Buffalo")).use { document ->
            assertEquals(4, document.numberOfPages)
            document.assertLandscape()
        }
    }

    @Test
    fun perClassAllDocumentRendersEveryClassSequentiallyForTheBundlePreview() {
        val spec = spec(13)
        assertEquals(
            listOf("Grade 5 Buffalo", "Grade 5 Cheetah", "Grade 5 Elephant", "Grade 5 Lion", "Grade 5 Rhino"),
            perClassRenderer.classTags(spec),
        )
        Loader.loadPDF(perClassRenderer.render(spec, null)).use { document ->
            assertEquals(16, document.numberOfPages)
            document.assertLandscape()
            val text = document.fullText()
            CLASS_TAGS.forEach { tag -> assertTrue(text.contains(tag.uppercase()), "class block missing " + tag) }
            assertTrue(text.contains("ALL CLASSES"))
            assertTrue(text.contains("STUDENTS WITH INCOMPLETE MARKS"))
        }
    }

    @Test
    fun perClassUnknownFilterIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            perClassRenderer.render(spec(5), "Grade 9 Unicorn")
        }
    }

    @Test
    fun perClassClassTeacherIsBlankWhenUnknown() {
        val spec = spec(5).copy(classTeachers = emptyMap())
        val model = TraditionalReportModel.perClass(spec, "Grade 5 Rhino")
        assertEquals(null, model.classTeacher)
        Loader.loadPDF(perClassRenderer.render(spec, "Grade 5 Rhino")).use { document ->
            val text = document.fullText()
            assertTrue(text.contains("97 ranked"))
            assertFalse(text.contains("Class teacher:"), "unknown teacher must omit the clause")
        }
    }
}
