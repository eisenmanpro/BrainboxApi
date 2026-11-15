package com.afrithecus.brainbox.api.report.render

import com.afrithecus.brainbox.api.report.GradeAnalysisSpec
import com.afrithecus.brainbox.api.report.ReportProperties
import com.afrithecus.brainbox.api.traditional.web.ClassGradeAnalysisDto
import com.afrithecus.brainbox.api.traditional.web.GradingConfigDefaults
import com.afrithecus.brainbox.api.traditional.web.SubjectTeacherContributionDto
import com.afrithecus.brainbox.api.traditional.web.TraditionalGradeAnalysisDto
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject
import org.springframework.stereotype.Component
import java.io.ByteArrayOutputStream
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * TRADITIONAL_GRADE_ANALYSIS renderer (plan A10/A21, spec §2.5D + §2.5I).
 *
 * Page 1 is the class comparison the school sheet prints: one row per class with the
 * per-subject percentage MEAN and POS pair, the class mean of learner totals (TOTAL),
 * a learner-weighted GRADE MEAN row and the "strongest subject"/"widest class gap"
 * callouts. Pages 2+ are the owner-requested subject-teacher contribution charts: one
 * panel per exam subject, one horizontal bar per teacher's mean subject percentage with
 * the grade mean as a dashed reference line.
 *
 * The renderer never re-derives marks or bands: page-1 means/positions and the chart bar
 * means arrive from the service (D3/D10/D11/D13/D14), and the grading legend follows the
 * exam's session snapshot (D18) with the platform defaults as the fallback.
 */
@Component
class GradeAnalysisRenderer(private val properties: ReportProperties) {

    fun render(spec: GradeAnalysisSpec): ByteArray {
        val analysis = spec.analysis
        val subjects = orderedSubjects(analysis)
        val bands = resolvedBands(analysis)
        val classes = analysis.classes.sortedWith(compareBy({ it.overallPosition }, { it.classTag }))
        val tablePages = tablePages(classes, subjects)
        val panels = buildPanels(analysis, subjects)
        val chartPages = if (panels.isEmpty()) emptyList() else panels.chunked(PANELS_PER_CHART_PAGE)
        val totalPages = tablePages.size + chartPages.size

        PDDocument().use { document ->
            val theme = ReportTheme(document)
            val logo = ReportImages.brandingLogo(document, spec.branding.logoUrl, properties.embedLogo)
            var pageNo = 0
            tablePages.forEachIndexed { index, page ->
                pageNo++
                renderTablePage(
                    document = document,
                    theme = theme,
                    spec = spec,
                    logo = logo,
                    bands = bands,
                    page = page,
                    classes = classes,
                    subjects = subjects,
                    pageNo = pageNo,
                    totalPages = totalPages,
                    lastTablePage = index == tablePages.lastIndex,
                    stamp = index == tablePages.lastIndex && chartPages.isEmpty(),
                )
            }
            chartPages.forEachIndexed { index, pagePanels ->
                pageNo++
                renderChartPage(
                    document = document,
                    theme = theme,
                    spec = spec,
                    logo = logo,
                    panels = pagePanels,
                    chartPageIndex = index,
                    chartPages = chartPages.size,
                    pageNo = pageNo,
                    totalPages = totalPages,
                    stamp = index == chartPages.lastIndex,
                )
            }
            val out = ByteArrayOutputStream()
            document.save(out)
            return out.toByteArray()
        }
    }

    // ------------------------------------------------------------------ page 1

    private fun renderTablePage(
        document: PDDocument,
        theme: ReportTheme,
        spec: GradeAnalysisSpec,
        logo: PDImageXObject?,
        bands: List<GradingBand>,
        page: TablePage,
        classes: List<ClassGradeAnalysisDto>,
        subjects: List<String>,
        pageNo: Int,
        totalPages: Int,
        lastTablePage: Boolean,
        stamp: Boolean,
    ) {
        val analysis = spec.analysis
        ReportCanvas(document, theme, PAGE_WIDTH, PAGE_HEIGHT).use { canvas ->
            ReportFurniture.watermark(canvas, spec.branding)
            ReportFurniture.band(
                canvas,
                spec.branding,
                analysis.gradeLevel + " \u00b7 grade analysis",
                spec.title,
                termLine(analysis),
                BAND_H,
                logo,
            )
            ReportFurniture.strip(
                canvas,
                analysis.gradeLevel.uppercase(Locale.US) + " ANALYSIS - "
                    + analysis.term.uppercase(Locale.US) + " " + analysis.year,
                "Class comparison - subject means and positions" + subjectWindow(page, subjects),
                BAND_H,
                STRIP_H,
            )
            if (page.empty) {
                canvas.textC(
                    "No complete marks have been recorded for this exam yet.",
                    PAGE_WIDTH / 2f,
                    canvas.top(BAND_H + STRIP_H + 120f),
                    theme.sans,
                    10f,
                    ReportTheme.MUTED,
                )
                canvas.textC(
                    analysis.gradeLevel + "  \u00b7  " + termLine(analysis),
                    PAGE_WIDTH / 2f,
                    canvas.top(BAND_H + STRIP_H + 138f),
                    theme.sans,
                    8f,
                    ReportTheme.MUTED,
                )
                ReportFurniture.legend(canvas, MARGIN, 34f, bands)
            } else {
                drawClassTable(canvas, theme, analysis, classes, subjects, page, bands, lastTablePage)
            }
            if (stamp) ReportFurniture.horizontalStamp(canvas, spec.branding, PAGE_WIDTH - MARGIN - 118f, STAMP_Y)
            ReportFurniture.footer(canvas, footerLeft(spec), pageNo, totalPages)
        }
    }

    private fun drawClassTable(
        canvas: ReportCanvas,
        theme: ReportTheme,
        analysis: TraditionalGradeAnalysisDto,
        classes: List<ClassGradeAnalysisDto>,
        subjects: List<String>,
        page: TablePage,
        bands: List<GradingBand>,
        lastTablePage: Boolean,
    ) {
        val chunk = page.chunk
        val rows = page.rows
        val left = MARGIN
        val width = tableWidth(chunk.size)
        val headerTop = BAND_H + STRIP_H
        val headBottom = canvas.top(headerTop + HEADER_H)
        canvas.fillRect(left, headBottom, width, HEADER_H, ReportTheme.NAVY)

        val base1 = canvas.top(headerTop + 11f)
        val base2 = canvas.top(headerTop + HEADER_H - 9f)
        val headerMuted = ReportTheme.mix(ReportTheme.PAPER, ReportTheme.NAVY, 0.28f)
        canvas.tracked("CLASS", left + 6f, base1, theme.sansBold, 5.6f, 1.2f, ReportTheme.PAPER)
        canvas.tracked("CLASS TEACHER", left + CLASS_W + 6f, base1, theme.sansBold, 5.6f, 1.2f, ReportTheme.PAPER)

        val subjectLeft = left + CLASS_W + TEACHER_W
        chunk.forEachIndexed { offset, subjectIndex ->
            val px = subjectLeft + offset * SUBJECT_PAIR_W
            canvas.textC(abbreviation(subjects[subjectIndex]), px + SUBJECT_PAIR_W / 2f, base1, theme.sansBold, 6f, ReportTheme.PAPER)
            canvas.textC("MEAN", px + MEAN_W / 2f, base2, theme.sans, 4.6f, headerMuted)
            canvas.textC("POS", px + MEAN_W + POS_W / 2f, base2, theme.sans, 4.6f, headerMuted)
        }
        val overallLeft = subjectLeft + chunk.size * SUBJECT_PAIR_W
        canvas.textC("OVERALL", overallLeft + (OVERALL_MEAN_W + OVERALL_POS_W) / 2f, base1, theme.sansBold, 5.4f, ReportTheme.PAPER)
        canvas.textC("MEAN", overallLeft + OVERALL_MEAN_W / 2f, base2, theme.sans, 4.6f, headerMuted)
        canvas.textC("POS", overallLeft + OVERALL_MEAN_W + OVERALL_POS_W / 2f, base2, theme.sans, 4.6f, headerMuted)

        val bestBySubject = chunk.associateWith { subjectIndex -> bestClassTag(classes, subjects[subjectIndex]) }
        val tableTop = headerTop + HEADER_H
        rows.forEachIndexed { rowIndex, row ->
            val rowTop = tableTop + rowIndex * ROW_H
            val rowBottom = canvas.top(rowTop + ROW_H)
            if (rowIndex % 2 == 1) canvas.fillRect(left, rowBottom, width, ROW_H, ReportTheme.MINT_SOFT)
            canvas.line(left, rowBottom, left + width, rowBottom, ReportTheme.LINE2, 0.4f)
            topThreeAccent(row.overallPosition)?.let { canvas.fillRect(left, rowBottom, 2.6f, ROW_H, it) }
            val centre = rowBottom + ROW_H / 2f
            canvas.fillRound(left + 6f, rowBottom + 7f, 12f, 12f, 6f, if (row.overallPosition == 1) ReportTheme.GOLD2_SOFT else ReportTheme.MIST)
            canvas.textC(row.overallPosition.toString(), left + 12f, baseline(centre, 6.2f), theme.sansBold, 6.2f, ReportTheme.NAVY)
            canvas.text(row.classTag, left + 24f, baseline(centre, 8.2f), theme.sansBold, 8.2f, ReportTheme.INK)
            val teacher = row.teacherName.trim()
            if (teacher.isNotEmpty()) {
                canvas.text(
                    canvas.ellipsis(teacher, theme.sans, 7f, TEACHER_W - 10f),
                    left + CLASS_W + 6f,
                    baseline(centre, 7f),
                    theme.sans,
                    7f,
                    ReportTheme.MUTED,
                )
            }
            var px = subjectLeft
            chunk.forEach { subjectIndex ->
                if (bestBySubject[subjectIndex] == row.classTag) {
                    canvas.fillRect(px, rowBottom, SUBJECT_PAIR_W, ROW_H, ReportTheme.MINT)
                }
                val name = subjects[subjectIndex]
                val mean = row.subjectAverages[name]
                val position = row.subjectPositions[name]
                canvas.textR(
                    if (mean == null) "-" else ReportTheme.pct(mean),
                    px + MEAN_W - 5f,
                    baseline(centre, 7.2f),
                    theme.sansBold,
                    7.2f,
                    ReportTheme.INK,
                )
                canvas.textC(
                    if (position == null) "-" else position.toString(),
                    px + MEAN_W + POS_W / 2f,
                    baseline(centre, 6.6f),
                    theme.sans,
                    6.6f,
                    ReportTheme.MUTED,
                )
                px += SUBJECT_PAIR_W
            }
            canvas.textR(
                ReportTheme.oneDecimal(row.totalAverage),
                overallLeft + OVERALL_MEAN_W - 5f,
                baseline(centre, 8f),
                theme.sansBold,
                8f,
                ReportTheme.NAVY,
            )
            canvas.textC(
                row.overallPosition.toString(),
                overallLeft + OVERALL_MEAN_W + OVERALL_POS_W / 2f,
                baseline(centre, 6.8f),
                theme.sansBold,
                6.8f,
                ReportTheme.NAVY,
            )
        }

        // Learner-weighted GRADE MEAN row (Σ classMean × classSize ÷ Σ classSize, D3).
        val gradeMeanTop = tableTop + rows.size * ROW_H
        val gradeMeanBottom = canvas.top(gradeMeanTop + GRADE_MEAN_H)
        canvas.fillRect(left, gradeMeanBottom, width, GRADE_MEAN_H, ReportTheme.MINT_SOFT)
        canvas.line(left, gradeMeanBottom, left + width, gradeMeanBottom, ReportTheme.GOLD, 1f)
        val gradeBase = gradeMeanBottom + 14f
        canvas.tracked("GRADE MEAN", left + 8f, gradeBase, theme.sansBold, 6f, 1.1f, ReportTheme.NAVY)
        canvas.textR(
            if (usesLearnerWeights(classes)) "learner-weighted" else "unweighted (no class sizes)",
            left + CLASS_W + TEACHER_W - 6f,
            gradeBase,
            theme.sans,
            5.4f,
            ReportTheme.MUTED,
        )
        val subjectMeans = weightedSubjectMeans(analysis, subjects)
        var px = subjectLeft
        chunk.forEach { subjectIndex ->
            val mean = subjectMeans.getOrNull(subjectIndex)
            canvas.textR(
                if (mean == null) "-" else ReportTheme.pct(mean),
                px + MEAN_W - 5f,
                gradeBase,
                theme.sansBold,
                7.4f,
                ReportTheme.TEAL_DARK,
            )
            px += SUBJECT_PAIR_W
        }
        val overall = weightedOverallMean(analysis)
        canvas.textR(
            if (overall == null) "-" else ReportTheme.oneDecimal(overall),
            overallLeft + OVERALL_MEAN_W - 5f,
            gradeBase,
            theme.sansBold,
            8f,
            ReportTheme.TEAL_DARK,
        )

        if (lastTablePage) {
            val calloutTop = gradeMeanTop + GRADE_MEAN_H + 16f
            drawCallouts(canvas, theme, analysis, subjects, calloutTop)
            canvas.text(legendNote(classes), left, 46f, theme.sans, 6.4f, ReportTheme.MUTED)
            ReportFurniture.legend(canvas, left, 34f, bands)
        }
    }

    private fun drawCallouts(
        canvas: ReportCanvas,
        theme: ReportTheme,
        analysis: TraditionalGradeAnalysisDto,
        subjects: List<String>,
        top: Float,
    ) {
        val strongest = strongestSubject(analysis, subjects)
        val widest = widestClassGap(analysis, subjects)
        val width = (PAGE_WIDTH - MARGIN * 2 - 12f) / 2f
        callout(canvas, theme, MARGIN, top, width, "Strongest subject", strongest.first, strongest.second)
        callout(canvas, theme, MARGIN + width + 12f, top, width, "Widest class gap", widest.first, widest.second)
    }

    private fun callout(
        canvas: ReportCanvas,
        theme: ReportTheme,
        x: Float,
        top: Float,
        width: Float,
        label: String,
        value: String,
        detail: String,
    ) {
        val height = 42f
        canvas.box(x, canvas.top(top + height), width, height, 4f, ReportTheme.PAPER, ReportTheme.LINE2, 0.7f)
        canvas.fillRect(x, canvas.top(top + height), 2.4f, height, ReportTheme.TEAL2)
        canvas.tracked(label.uppercase(Locale.US), x + 10f, canvas.top(top + 12f), theme.sansBold, 5.4f, 1.2f, ReportTheme.MUTED)
        canvas.text(value, x + 10f, canvas.top(top + 27f), theme.sansBold, 9.2f, ReportTheme.INK)
        canvas.textR(detail, x + width - 8f, canvas.top(top + 27f), theme.sans, 6.4f, ReportTheme.MUTED)
    }

    // ------------------------------------------------------------- pages 2+

    private fun renderChartPage(
        document: PDDocument,
        theme: ReportTheme,
        spec: GradeAnalysisSpec,
        logo: PDImageXObject?,
        panels: List<Panel>,
        chartPageIndex: Int,
        chartPages: Int,
        pageNo: Int,
        totalPages: Int,
        stamp: Boolean,
    ) {
        val analysis = spec.analysis
        ReportCanvas(document, theme, PAGE_WIDTH, PAGE_HEIGHT).use { canvas ->
            ReportFurniture.watermark(canvas, spec.branding)
            ReportFurniture.band(
                canvas,
                spec.branding,
                analysis.gradeLevel + " \u00b7 teacher contribution",
                spec.title,
                termLine(analysis),
                BAND_H,
                logo,
            )
            ReportFurniture.strip(
                canvas,
                "SUBJECT TEACHER CONTRIBUTION - " + analysis.gradeLevel.uppercase(Locale.US)
                    + " - " + analysis.term.uppercase(Locale.US) + " " + analysis.year,
                "Mean subject percentage by subject teacher   \u00b7   chart page "
                    + (chartPageIndex + 1) + " of " + chartPages,
                BAND_H,
                STRIP_H,
            )
            panels.forEachIndexed { index, panel ->
                val column = index % CHART_COLUMNS
                val row = index / CHART_COLUMNS
                val x = MARGIN + column * (PANEL_WIDTH + PANEL_GAP)
                val top = CHART_TOP + row * (PANEL_HEIGHT + PANEL_GAP)
                drawPanel(canvas, theme, x, top, PANEL_WIDTH, PANEL_HEIGHT, panel)
            }
            canvas.text(FOOTNOTE, MARGIN, 38f, theme.sansItalic, 6f, ReportTheme.MUTED)
            if (stamp) ReportFurniture.horizontalStamp(canvas, spec.branding, PAGE_WIDTH - MARGIN - 118f, STAMP_Y)
            ReportFurniture.footer(canvas, footerLeft(spec), pageNo, totalPages)
        }
    }

    private fun drawPanel(
        canvas: ReportCanvas,
        theme: ReportTheme,
        x: Float,
        top: Float,
        width: Float,
        height: Float,
        panel: Panel,
    ) {
        canvas.box(x, canvas.top(top + height), width, height, 4f, ReportTheme.PAPER, ReportTheme.LINE2, 0.7f)
        canvas.fillRect(x, canvas.top(top + height), 2.4f, 14f, ReportTheme.GOLD2)
        canvas.text(panel.title.uppercase(Locale.US), x + 8f, canvas.top(top + 13f), theme.sansBold, 7f, ReportTheme.NAVY)
        val caption = when {
            panel.empty -> "no complete marks"
            panel.bars.all { it.unassigned } ->
                "no current assignment   \u00b7   grade mean " + ReportTheme.pct(panel.gradeMean)
            else -> "grade mean " + ReportTheme.pct(panel.gradeMean)
        }
        canvas.text(caption, x + 8f, canvas.top(top + 22f), theme.sans, 5.8f, ReportTheme.MUTED)
        if (panel.empty) {
            canvas.textC("No complete marks", x + width / 2f, canvas.top(top + height / 2f), theme.sansBold, 8f, ReportTheme.MUTED)
            canvas.textC(
                "No learner had a complete mark for this subject",
                x + width / 2f,
                canvas.top(top + height / 2f + 12f),
                theme.sans,
                5.8f,
                ReportTheme.MUTED,
            )
            return
        }
        val columns = if (panel.bars.size > BARS_PER_COLUMN) 2 else 1
        val columnGap = 8f
        val columnWidth = (width - 16f - columnGap * (columns - 1)) / columns
        val perColumn = ceil(panel.bars.size / columns.toDouble()).toInt()
        val labelWidth = if (columns == 1) 88f else 44f
        val valueWidth = if (columns == 1) 30f else 20f
        for (column in 0 until columns) {
            val bars = panel.bars.drop(column * perColumn).take(perColumn)
            if (bars.isEmpty()) continue
            drawBarColumn(
                canvas = canvas,
                theme = theme,
                x = x + 8f + column * (columnWidth + columnGap),
                top = top,
                height = height,
                columnWidth = columnWidth,
                labelWidth = labelWidth,
                valueWidth = valueWidth,
                panel = panel,
                bars = bars,
                dense = columns == 2,
            )
        }
    }

    private fun drawBarColumn(
        canvas: ReportCanvas,
        theme: ReportTheme,
        x: Float,
        top: Float,
        height: Float,
        columnWidth: Float,
        labelWidth: Float,
        valueWidth: Float,
        panel: Panel,
        bars: List<Bar>,
        dense: Boolean,
    ) {
        val chartX = x + labelWidth
        val chartWidth = columnWidth - labelWidth - valueWidth
        val axisTop = top + 34f
        val axisBottom = top + height - 20f
        val labelFont = if (dense) 5f else 5.6f
        val valueFont = if (dense) 5.2f else 5.8f

        listOf(0, 25, 50, 75, 100).forEach { gridPercent ->
            val gridX = chartX + chartWidth * gridPercent / 100f
            canvas.line(
                gridX,
                canvas.top(axisBottom),
                gridX,
                canvas.top(axisTop),
                ReportTheme.LINE2,
                if (gridPercent == 0) 0.8f else 0.4f,
            )
            canvas.textC(gridPercent.toString(), gridX, canvas.top(axisBottom + 7f), theme.sans, 4.2f, ReportTheme.MUTED)
        }
        if (panel.gradeMean > 0.0) {
            val referenceX = chartX + chartWidth * (min(100.0, panel.gradeMean).toFloat() / 100f)
            canvas.dashedLine(referenceX, canvas.top(axisBottom), referenceX, canvas.top(axisTop), ReportTheme.GOLD2_DEEP, 0.7f)
            // Above the plot area so the label never sits on the first bar (§2.5I).
            canvas.text("mean", referenceX + 2f, canvas.top(axisTop - 3f), theme.sans, 4.4f, ReportTheme.GOLD2_DEEP)
        }

        val barGap = 4f
        val barHeight = min(12f, (axisBottom - axisTop - 6f) / max(1, bars.size) - barGap).coerceAtLeast(4f)
        val highestTeacherMean = panel.highestMean
        bars.forEachIndexed { index, bar ->
            val barTop = axisTop + index * (barHeight + barGap)
            val barBottom = canvas.top(barTop + barHeight)
            val filledWidth = max(1.5f, chartWidth * (min(100.0, bar.mean).toFloat() / 100f))
            val fill = when {
                bar.unassigned -> ReportTheme.mix(ReportTheme.MUTED2, ReportTheme.PAPER, 0.45f)
                highestTeacherMean != null && abs(bar.mean - highestTeacherMean) < 1e-9 -> ReportTheme.GOLD2
                bar.mean >= panel.gradeMean -> ReportTheme.TEAL2
                else -> ReportTheme.mix(ReportTheme.MUTED2, ReportTheme.PAPER, 0.45f)
            }
            canvas.fillRect(chartX, barBottom, filledWidth, barHeight, fill)
            val baseline = barBottom + max(0f, (barHeight - labelFont * 0.72f) / 2f)
            canvas.text(
                canvas.ellipsis(bar.label, theme.sans, labelFont, labelWidth - 4f),
                x,
                baseline,
                theme.sans,
                labelFont,
                if (bar.unassigned) ReportTheme.MUTED else ReportTheme.INK,
            )
            val value = ReportTheme.oneDecimal(bar.mean)
            val valueX = min(chartX + filledWidth + 3f, chartX + chartWidth - canvas.textWidth(value, theme.sansBold, valueFont))
            canvas.text(value, valueX, baseline, theme.sansBold, valueFont, ReportTheme.NAVY)
        }
    }

    // ------------------------------------------------------------------ plan

    private data class TablePage(
        val chunk: List<Int>,
        val rows: List<ClassGradeAnalysisDto>,
        val empty: Boolean,
    )

    private fun tablePages(classes: List<ClassGradeAnalysisDto>, subjects: List<String>): List<TablePage> {
        val chunks = subjectChunks(subjects)
        if (classes.isEmpty()) return listOf(TablePage(chunks.first(), emptyList(), true))
        return chunks.flatMap { chunk ->
            classes.chunked(CLASS_ROWS_PER_PAGE).map { rows -> TablePage(chunk, rows, false) }
        }
    }

    private fun subjectChunks(subjects: List<String>): List<List<Int>> {
        if (subjects.isEmpty()) return listOf(emptyList())
        return subjects.indices.toList().chunked(subjectsPerTablePage())
    }

    private fun subjectWindow(page: TablePage, subjects: List<String>): String {
        if (subjects.size <= subjectsPerTablePage()) return ""
        val first = page.chunk.firstOrNull() ?: return ""
        return "   \u00b7   subjects " + (first + 1) + "-" + (page.chunk.last() + 1) + " of " + subjects.size
    }

    private fun tableWidth(subjectColumns: Int): Float =
        CLASS_W + TEACHER_W + subjectColumns * SUBJECT_PAIR_W + OVERALL_MEAN_W + OVERALL_POS_W

    // ------------------------------------------------------------------ data

    private data class Bar(
        val label: String,
        val mean: Double,
        val unassigned: Boolean = false,
    )

    private data class Panel(
        val title: String,
        /** Highest teacher mean across every panel of the subject (the gold bar, §2.5I). */
        val highestMean: Double?,
        val bars: List<Bar>,
        val gradeMean: Double,
        val empty: Boolean,
    )

    private fun buildPanels(
        analysis: TraditionalGradeAnalysisDto,
        subjects: List<String>,
    ): List<Panel> {
        val pageMeans = weightedSubjectMeans(analysis, subjects)
        val panels = mutableListOf<Panel>()
        analysis.subjectTeacherContributions.forEach { contribution ->
            val subjectIndex = subjects.indexOfFirst { it.equals(contribution.subjectName.trim(), ignoreCase = true) }
            val pageMean = if (subjectIndex >= 0) pageMeans.getOrNull(subjectIndex) else null
            val gradeMean = contribution.gradeMean.takeIf { it > 0.0 } ?: pageMean ?: 0.0
            val bars = barsFor(contribution)
            if (bars.isEmpty()) {
                panels.add(Panel(contribution.subjectName, null, emptyList(), gradeMean, true))
                return@forEach
            }
            val highestMean = bars.filterNot { it.unassigned }.maxOfOrNull { it.mean }
            bars.chunked(BARS_PER_PANEL).forEachIndexed { index, chunk ->
                val title = if (index == 0) contribution.subjectName else contribution.subjectName + " (continued)"
                panels.add(Panel(title, highestMean, chunk, gradeMean, false))
            }
        }
        return panels
    }

    private fun barsFor(contribution: SubjectTeacherContributionDto): List<Bar> {
        val bars = contribution.teachers
            .sortedByDescending { it.mean }
            .map { teacher ->
                val label = when {
                    teacher.former -> "Former teacher"
                    teacher.teacherName.isBlank() -> "Teacher"
                    else -> teacher.teacherName.trim()
                } + if (teacher.shared && !teacher.former) " (shared)" else ""
                Bar(label + tagsSuffix(teacher.classTags), teacher.mean)
            }
            .toMutableList()
        contribution.unassigned?.takeIf { it.learners > 0 || it.mean > 0.0 }?.let {
            bars.add(Bar("Unassigned", it.mean, unassigned = true))
        }
        return bars
    }

    private fun tagsSuffix(tags: List<String>): String {
        val cleaned = tags.map { it.trim() }.filter { it.isNotEmpty() }
        return if (cleaned.isEmpty()) "" else " - " + cleaned.joinToString(", ")
    }

    // ------------------------------------------------------------- derivations

    private fun bestClassTag(classes: List<ClassGradeAnalysisDto>, subject: String): String? {
        var best: ClassGradeAnalysisDto? = null
        var bestMean = Double.NEGATIVE_INFINITY
        classes.forEach { row ->
            val mean = row.subjectAverages[subject] ?: return@forEach
            if (mean > bestMean) {
                bestMean = mean
                best = row
            }
        }
        return best?.classTag
    }

    private fun strongestSubject(
        analysis: TraditionalGradeAnalysisDto,
        subjects: List<String>,
    ): Pair<String, String> {
        val means = weightedSubjectMeans(analysis, subjects)
        var bestIndex = -1
        var bestMean = Double.NEGATIVE_INFINITY
        means.forEachIndexed { index, mean ->
            if (mean != null && mean > bestMean) {
                bestMean = mean
                bestIndex = index
            }
        }
        if (bestIndex < 0) return "-" to ""
        return subjects[bestIndex] to ReportTheme.pct(bestMean)
    }

    private fun widestClassGap(
        analysis: TraditionalGradeAnalysisDto,
        subjects: List<String>,
    ): Pair<String, String> {
        var bestIndex = -1
        var bestGap = Double.NEGATIVE_INFINITY
        var bestLow = 0.0
        var bestHigh = 0.0
        subjects.forEachIndexed { index, subject ->
            val values = analysis.classes.mapNotNull { it.subjectAverages[subject] }
            if (values.size < 2) return@forEachIndexed
            val low = values.min()
            val high = values.max()
            if (high - low > bestGap) {
                bestGap = high - low
                bestIndex = index
                bestLow = low
                bestHigh = high
            }
        }
        if (bestIndex < 0) return "-" to ""
        return subjects[bestIndex] to (ReportTheme.pct(bestLow) + " - " + ReportTheme.pct(bestHigh))
    }

    private fun legendNote(classes: List<ClassGradeAnalysisDto>): String {
        val weighting = if (usesLearnerWeights(classes)) "learner-weighted GRADE MEAN" else "GRADE MEAN (unweighted)"
        return "MEAN = percentage mean for the subject   \u00b7   TOTAL = class mean of learner totals " +
            "(sum of subject %)   \u00b7   POS = rank among the grade's classes (1 = best)   \u00b7   " +
            weighting + " at the foot"
    }

    private fun footerLeft(spec: GradeAnalysisSpec): String =
        spec.branding.schoolName + "   \u00b7   " + termLine(spec.analysis)

    private fun termLine(analysis: TraditionalGradeAnalysisDto): String =
        analysis.term + " " + analysis.year

    private fun topThreeAccent(position: Int): java.awt.Color? = when (position) {
        1 -> ReportTheme.GOLD2
        2 -> ReportTheme.mix(ReportTheme.MUTED2, ReportTheme.PAPER, 0.35f)
        3 -> ReportTheme.AMBER
        else -> null
    }

    private fun baseline(centre: Float, font: Float): Float = centre - 0.34f * font

    // ------------------------------------------------------- static geometry

    companion object {
        internal const val PAGE_WIDTH = 841.89f
        internal const val PAGE_HEIGHT = 595.28f

        private const val MARGIN = 36f
        private const val BAND_H = 76f
        private const val STRIP_H = 28f
        private const val HEADER_H = 32f
        private const val ROW_H = 26f
        private const val GRADE_MEAN_H = 22f
        private const val CLASS_W = 110f
        private const val TEACHER_W = 130f
        private const val MEAN_W = 44f
        private const val POS_W = 28f
        private const val SUBJECT_PAIR_W = MEAN_W + POS_W
        private const val OVERALL_MEAN_W = 60f
        private const val OVERALL_POS_W = 32f
        private const val STAMP_Y = 30f

        internal const val CLASS_ROWS_PER_PAGE = 10
        internal const val PANELS_PER_CHART_PAGE = 6
        internal const val CHART_COLUMNS = 3
        private const val CHART_TOP = BAND_H + STRIP_H + 6f
        private const val PANEL_GAP = 12f
        private const val FOOTNOTE_BAND = 78f
        private val PANEL_WIDTH = (PAGE_WIDTH - MARGIN * 2 - PANEL_GAP * (CHART_COLUMNS - 1)) / CHART_COLUMNS
        private val PANEL_HEIGHT = (PAGE_HEIGHT - CHART_TOP - FOOTNOTE_BAND - PANEL_GAP) / 2f
        private const val BARS_PER_COLUMN = 8
        internal const val BARS_PER_PANEL = 16

        internal const val FOOTNOTE =
            "Teacher means use complete marks, attributed from current class/subject assignments; " +
                "later reassignments change this page, not the marks."

        private val ABBREVIATIONS = mapOf(
            "mathematics" to "MATH",
            "maths" to "MATH",
            "english" to "ENG",
            "kiswahili" to "KISW",
            "integrated science" to "INT",
            "science" to "SCI",
            "social studies" to "SST",
            "social studies & cre" to "SST",
            "cre" to "CRE",
            "c.r.e" to "CRE",
            "i.r.e" to "IRE",
            "religious education" to "RE",
            "agriculture" to "AGRI",
            "agriculture & nutrition" to "AGRI & NUT",
            "creative arts" to "CRA",
            "physical & health education" to "PHE",
            "physical and health education" to "PHE",
            "pre-technical studies" to "PRETECH",
            "business studies" to "BUS",
            "home science" to "HSC",
            "computer science" to "COMP",
            "music" to "MUS",
            "art & craft" to "ART",
            "french" to "FRE",
            "german" to "GER",
            "environmental activities" to "ENV",
            "life skills" to "LIFE",
            "science & technology" to "SCI & TECH",
            "science and technology" to "SCI & TECH",
        )

        /** Fixed header map (§2.4): the analysis columns are 72 pt so short labels only. */
        internal fun abbreviation(name: String): String {
            val key = name.trim().lowercase(Locale.US)
            ABBREVIATIONS[key]?.let { return it }
            val words = key.split(Regex("\\s+")).filter { it.isNotBlank() && it != "&" && it != "and" }
            if (words.size == 1) return words.first().uppercase(Locale.US).take(5)
            val initials = words.mapNotNull { it.firstOrNull() }.joinToString("").uppercase(Locale.US)
            return if (initials.length >= 2) initials.take(5) else key.uppercase(Locale.US).take(5)
        }

        /** Exam subject order (D10); the class maps' key order is only a legacy fallback. */
        internal fun orderedSubjects(analysis: TraditionalGradeAnalysisDto): List<String> {
            if (analysis.subjects.isNotEmpty()) return analysis.subjects.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            return analysis.classes.flatMap { it.subjectAverages.keys }.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        }

        /** Session bands (D18) with the platform defaults as the fallback; always descending. */
        internal fun resolvedBands(analysis: TraditionalGradeAnalysisDto): List<GradingBand> {
            val bands = analysis.grading?.bands?.takeIf { it.isNotEmpty() } ?: GradingConfigDefaults.bands()
            return bands
                .filter { it.grade.isNotBlank() }
                .sortedByDescending { it.minPercentage }
                .map { GradingBand(it.grade.trim().uppercase(Locale.US), it.minPercentage.toDouble().coerceIn(0.0, 100.0)) }
        }

        internal fun usesLearnerWeights(classes: List<ClassGradeAnalysisDto>): Boolean =
            classes.sumOf { it.studentCount.coerceAtLeast(0) } > 0

        /** Σ(classMean × studentCount) ÷ Σ studentCount (D3); unweighted when no class has a size. */
        internal fun weightedSubjectMeans(
            analysis: TraditionalGradeAnalysisDto,
            subjects: List<String> = orderedSubjects(analysis),
        ): List<Double?> = subjects.map { subject ->
            weightedMean(analysis.classes.mapNotNull { it.subjectAverages[subject]?.let { mean -> mean to it.studentCount } })
        }

        /** Learner-weighted GRADE MEAN of the OVERALL column (the class mean of learner totals). */
        internal fun weightedOverallMean(analysis: TraditionalGradeAnalysisDto): Double? =
            weightedMean(analysis.classes.map { it.totalAverage to it.studentCount })

        private fun weightedMean(values: List<Pair<Double, Int>>): Double? {
            if (values.isEmpty()) return null
            val weight = values.sumOf { it.second.coerceAtLeast(0) }
            if (weight > 0) {
                return values.sumOf { it.first * it.second.coerceAtLeast(0) } / weight
            }
            return values.sumOf { it.first } / values.size
        }

        private fun subjectsPerTablePage(): Int = floor(
            (PAGE_WIDTH - MARGIN * 2 - CLASS_W - TEACHER_W - OVERALL_MEAN_W - OVERALL_POS_W) / SUBJECT_PAIR_W,
        ).toInt().coerceAtLeast(1)
    }
}
