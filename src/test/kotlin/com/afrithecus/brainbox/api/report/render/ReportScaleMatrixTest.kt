package com.afrithecus.brainbox.api.report.render

import com.afrithecus.brainbox.api.report.ReportProperties
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.text.PDFTextStripper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A17 - the 500-learner scale fixture asserted numerically across the A20 orientation matrix.
 *
 * The fixture (ReportScaleFixtures) is the lab's 500 learners / five classes with ties, absences,
 * a missing subject, long and accented names. Everything the school checks is recomputed here
 * independently from the DTO rows (never from the render model): TOTAL = sum of subject
 * percentages, MEAN = TOTAL / subject count, subject mean = mean percentage, positions unique and
 * monotonic with the tie rule, and the analysis class positions and teacher bars reconciling with
 * the page-1 means.
 */
class ReportScaleMatrixTest {

    private val combined = TraditionalCombinedRenderer(ReportProperties(embedLogo = false))
    private val perClass = TraditionalPerClassRenderer(ReportProperties(embedLogo = false))
    private val analysisRenderer = GradeAnalysisRenderer(ReportProperties(embedLogo = false))

    private fun PDDocument.assertPageSize(width: Float, height: Float) {
        for (index in 0 until numberOfPages) {
            val box = getPage(index).mediaBox
            assertEquals(width, box.width, 0.05f, "page " + (index + 1) + " width")
            assertEquals(height, box.height, 0.05f, "page " + (index + 1) + " height")
        }
    }

    private fun flat(value: String): String = value.filterNot { it.isWhitespace() }

    private fun pages(bytes: ByteArray): List<String> = Loader.loadPDF(bytes).use { document ->
        (0 until document.numberOfPages).map { index ->
            PDFTextStripper().apply {
                startPage = index + 1
                endPage = index + 1
            }.getText(document)
        }
    }

    @Test
    fun orientationMatrixRendersTheApprovedGeometryAndPageCounts() {
        val expected = mapOf(
            5 to Triple(false, 41, 13),
            9 to Triple(true, 31, 17),
            13 to Triple(true, 35, 15),
        )
        expected.forEach { (subjectCount, geometry) ->
            val (landscape, rowsPerPage, pageCount) = geometry
            val spec = ReportScaleFixtures.scaleSpec(subjectCount)
            val model = TraditionalReportModel.combined(spec)
            val layout = model.layout

            assertEquals(landscape, layout.landscape, "orientation at S=" + subjectCount)
            assertEquals(rowsPerPage, layout.rowsPerPage, "rows per page at S=" + subjectCount)
            assertEquals(subjectCount, model.subjects.size)
            assertEquals(485, model.rankedCount, "complete learners at S=" + subjectCount)
            assertEquals(15, model.incompleteCount, "incomplete learners at S=" + subjectCount)

            val planned = layout.plan(model.rankedCount).size + layout.incompletePages(model.incompleteCount)
            Loader.loadPDF(combined.render(spec)).use { document ->
                assertEquals(pageCount, document.numberOfPages, "rendered pages at S=" + subjectCount)
                assertEquals(planned, document.numberOfPages, "plan/rendered agreement at S=" + subjectCount)
                if (landscape) document.assertPageSize(841.89f, 595.28f) else document.assertPageSize(595.28f, 841.89f)
                for (index in 0 until document.numberOfPages) {
                    PDFTextStripper().apply { startPage = index + 1; endPage = index + 1 }
                        .getText(document)
                        .let { text -> assertTrue(text.contains("MATH"), "header missing on page " + (index + 1)) }
                }
                val all = PDFTextStripper().getText(document)
                assertTrue(all.contains("OVERALL"), "totals block missing at S=" + subjectCount)
                assertTrue(all.contains("STUDENTS WITH INCOMPLETE MARKS"), "incomplete section missing at S=" + subjectCount)
            }
        }
    }

    @Test
    fun totalsMeansAndRanksFollowTheWorkbookFormulasAtEveryOrientation() {
        listOf(5, 9, 13).forEach { subjectCount ->
            val spec = ReportScaleFixtures.scaleSpec(subjectCount)
            val model = TraditionalReportModel.combined(spec)
            val complete = spec.rows.filter { row ->
                row.subjects.size == subjectCount && row.subjects.all { it.rawScore > 0 }
            }

            // TOTAL/MEAN per learner recomputed from the DTO, not from the model.
            assertEquals(complete.size, model.ranked.size)
            complete.forEach { dto ->
                val row = model.ranked.first { it.studentId == dto.studentId }
                assertEquals(dto.totalPercentage, row.total, 1e-9)
                assertEquals(dto.totalPercentage / subjectCount, row.mean, 1e-9)
                assertEquals(dto.totalPercentage, row.cells.sumOf { it.percentage }, 1e-9)
            }

            // Subject means and the overall mean (mean of the printed MEAN column).
            val expectedSubjectMeans = spec.exam.subjects.mapIndexed { index, _ ->
                complete.sumOf { it.subjects.getOrNull(index)?.percentage ?: 0.0 } / complete.size
            }
            expectedSubjectMeans.forEachIndexed { index, mean ->
                assertEquals(mean, model.subjectMeans[index], 1e-9, "subject mean " + index + " at S=" + subjectCount)
            }
            val expectedOverallMean = complete.sumOf { it.totalPercentage / subjectCount } / complete.size
            assertEquals(expectedOverallMean, model.overallMean, 1e-9)
            assertEquals(TraditionalReportModel.levelFor(expectedOverallMean, model.bands), model.overallGrade)
            assertEquals(complete.sumOf { it.totalPercentage }, model.learnerTotalSum, 1e-9)

            // Positions are unique, sequential, and ordered by TOTAL desc then name asc.
            val comparator = compareByDescending<TraditionalListRow> { it.total }
                .thenBy { it.studentName.lowercase() }
                .thenBy { it.studentName }
            assertEquals(model.ranked.sortedWith(comparator).map { it.studentId }, model.ranked.map { it.studentId })
            assertEquals((1..485).toList(), model.ranked.map { it.position })
            assertTrue(model.ranked.zipWithNext().all { (a, b) -> a.total >= b.total })
        }
    }

    @Test
    fun perClassDocumentsReRankWithinTheClassAndBundlePageCountsMatchThePlan() {
        assertEquals(
            listOf("Grade 5 Buffalo", "Grade 5 Cheetah", "Grade 5 Elephant", "Grade 5 Lion", "Grade 5 Rhino"),
            perClass.classTags(ReportScaleFixtures.scaleSpec(5)),
        )

        ReportScaleFixtures.CLASS_TAGS.forEach { tag ->
            val spec = ReportScaleFixtures.scaleSpec(13)
            val model = TraditionalReportModel.perClass(spec, tag)
            assertEquals(97, model.rankedCount, "ranked learners in " + tag)
            assertEquals(3, model.incompleteCount, "incomplete learners in " + tag)
            assertEquals((1..97).toList(), model.ranked.map { it.position })
            assertTrue(model.ranked.zipWithNext().all { (a, b) -> a.total >= b.total })
            assertNotNull(model.classMean)
            assertEquals(model.ranked.sumOf { it.mean } / 97.0, model.classMean, 1e-9)
            assertTrue(model.ranked.all { it.classTag == tag })
            assertTrue(model.incomplete.all { it.classTag == tag })
            assertEquals(ReportScaleFixtures.CLASS_TEACHERS.getValue(tag), model.classTeacher)
        }

        // Single-class PDFs: Lion at S=5 is 4 pages (3 ranked + its own incomplete page).
        Loader.loadPDF(perClass.render(ReportScaleFixtures.scaleSpec(5), "Grade 5 Lion")).use { document ->
            assertEquals(4, document.numberOfPages)
            document.assertPageSize(595.28f, 841.89f)
            val text = PDFTextStripper().getText(document)
            assertTrue(text.contains("97 ranked"))
            assertTrue(text.contains("3 incomplete"))
            ReportScaleFixtures.CLASS_TAGS.filter { it != "Grade 5 Lion" }.forEach { other ->
                assertFalse(text.contains(other.substringAfterLast(' ')), "single-class PDF leaked " + other)
            }
        }

        // The lab/preview "All" document: one sequential file per class, shared incomplete section.
        listOf(5 to 16, 9 to 21, 13 to 16).forEach { (subjectCount, expectedPages) ->
            val spec = ReportScaleFixtures.scaleSpec(subjectCount)
            val layout = ReportTableLayout(subjectCount)
            val planned = ReportScaleFixtures.CLASS_TAGS.size * layout.plan(97).size + layout.incompletePages(15)
            Loader.loadPDF(perClass.render(spec, null)).use { document ->
                assertEquals(expectedPages, document.numberOfPages, "All bundle at S=" + subjectCount)
                assertEquals(planned, document.numberOfPages, "All bundle plan at S=" + subjectCount)
                val text = PDFTextStripper().getText(document)
                ReportScaleFixtures.CLASS_TAGS.forEach { tag ->
                    assertTrue(text.contains(tag.uppercase()), "class block missing " + tag)
                }
                // 1 incomplete page shared by every class.
                assertEquals(1, Regex("STUDENTS WITH INCOMPLETE MARKS").findAll(text).count())
            }
        }
    }

    @Test
    fun analysisPositionsArePrintedAndTeacherBarsReconcileWithThePageOneMeans() {
        val analysis = ReportScaleFixtures.analysis13()
        val pages = pages(analysisRenderer.render(ReportScaleFixtures.analysisSpec()))

        // 13 subjects: 3 table pages (6 columns each) + 3 chart pages (6 panels each).
        assertEquals(6, pages.size)
        assertEquals(3, pages.count { flat(it).contains("CLASSTEACHER") })

        val tablePage = flat(pages[0])
        // Rows are sorted by overall position: Cheetah (1) first, Lion (5) last.
        val expectedOrder = listOf("Grade 5 Cheetah", "Grade 5 Rhino", "Grade 5 Elephant", "Grade 5 Buffalo", "Grade 5 Lion")
        val order = expectedOrder.map { tablePage.indexOf(flat(it)) }
        assertTrue(order.all { it >= 0 }, "every class must be printed: " + order)
        assertEquals(order.sorted(), order)

        // Every printed MEAN+POS pair on page 1 equals the payload's class mean and position for
        // that subject (subject columns 0..5 = Mathematics .. CRE).
        val firstSix = analysis.subjects.take(6)
        firstSix.forEach { subject ->
            ReportScaleFixtures.CLASS_TAGS.forEachIndexed { classIndex, tag ->
                val row = analysis.classes.first { it.classTag == tag }
                val mean = row.subjectAverages.getValue(subject)
                val position = row.subjectPositions.getValue(subject)
                val pair = ReportTheme.pct(mean) + position
                assertTrue(tablePage.contains(pair), "missing " + subject + " " + pair + " for " + tag)
            }
        }
        // Overall MEAN and POS are printed for every class row.
        analysis.classes.forEach { row ->
            assertTrue(tablePage.contains(ReportTheme.oneDecimal(row.totalAverage)), "overall mean missing for " + row.classTag)
        }

        // GRADE MEAN row (learner-weighted, D3): equal class sizes so it is the plain mean.
        val expectedGradeMean = analysis.classes.map { it.subjectAverages.getValue("Mathematics") }.average()
        assertEquals(50.0, expectedGradeMean, 1e-9)
        assertTrue(tablePage.contains("50.0%"), "weighted Mathematics grade mean missing")
        assertEquals(expectedGradeMean, GradeAnalysisRenderer.weightedSubjectMeans(analysis, listOf("Mathematics")).single()!!, 1e-9)

        // Bars reconcile: every mark behind the page-1 mean is accounted for, and the learner
        // weighted bar mean equals the printed subject mean for all 13 subjects.
        analysis.subjectTeacherContributions.forEach { contribution ->
            val bars = contribution.teachers
            val unassigned = contribution.unassigned
            assertEquals(100, bars.sumOf { it.learners } + (unassigned?.learners ?: 0), "learners behind " + contribution.subjectName)
            val weighted = (
                bars.sumOf { it.mean * it.learners } +
                    (unassigned?.let { it.mean * it.learners } ?: 0.0)
                ) / (bars.sumOf { it.learners } + (unassigned?.learners ?: 0))
            val pageMean = GradeAnalysisRenderer.weightedSubjectMeans(analysis, listOf(contribution.subjectName)).single()!!
            assertEquals(pageMean, weighted, 1e-9, "bars must reconcile for " + contribution.subjectName)
        }
        assertEquals(1, analysis.subjectTeacherContributions.count { it.unassigned != null })
        assertEquals(1, analysis.subjectTeacherContributions.sumOf { c -> c.teachers.count { it.shared } })
        assertEquals(1, analysis.subjectTeacherContributions.sumOf { c -> c.teachers.count { it.former } })

        // Chart pages carry the reconciled reference line and the L6 labels.
        val charts = flat(pages.drop(3).joinToString(""))
        assertTrue(charts.contains("SUBJECTTEACHER"), charts.take(160))
        assertTrue(charts.contains("50.0%"), "chart caption must print the page-1 mean")
        assertTrue(charts.contains("(shared)"))
        assertTrue(charts.contains("Formerteacher"))
        assertTrue(charts.contains("Unassigned"))
        assertTrue(charts.contains("currentclass/subjectassignments"))
    }

    @Test
    fun gradeMeanFallsBackToUnweightedWhenClassSizesAreUnknown() {
        val analysis = ReportScaleFixtures.analysis13().let { dto ->
            dto.copy(classes = dto.classes.map { it.copy(studentCount = 0) })
        }
        val pages = pages(analysisRenderer.render(ReportScaleFixtures.analysisSpec().copy(analysis = analysis)))
        val first = flat(pages[0])
        assertTrue(first.contains("unweighted(noclasssizes)"))
        // Unweighted Mathematics mean is 50.0 too (equal-sized fixture classes), so assert the
        // weighted 50.0 still prints but the label changes; the fallback path is what is under test.
        assertTrue(first.contains("50.0%"))
        assertNotNull(GradeAnalysisRenderer.weightedOverallMean(analysis))
    }
}
