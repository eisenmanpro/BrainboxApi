package com.afrithecus.brainbox.api.report.render

import com.afrithecus.brainbox.api.report.ReportBranding
import com.afrithecus.brainbox.api.report.ReportProperties
import com.afrithecus.brainbox.api.report.TeacherPerformanceSpec
import com.afrithecus.brainbox.api.report.TeacherStrandScore
import com.afrithecus.brainbox.api.report.TeacherSubjectPerformance
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject
import org.springframework.stereotype.Component
import java.awt.Color
import java.io.ByteArrayOutputStream

/**
 * TEACHER_PERFORMANCE report (type 4, v1 page 04, A6/D4/F10).
 *
 * One A4 landscape page describing **one teacher**: four KPI tiles (subjects taught, learners,
 * teacher average, top subject), the subject-delivery list with the level each subject lands in
 * and its average, the teacher-specific strand impact grouped by taught subject, a teacher-vs-class
 * strip with coverage, and the next-step callout. The figures come from TeacherPerformanceSpec,
 * which ReportDataService builds from the actor's own subjectTeacherPerformance rows - the class
 * report's class-wide strand list is never presented as this teacher's performance (F10).
 *
 * A teacher who teaches many subjects in the class can exceed one page; the layout compresses and
 * then truncates the strand list with an explicit "showing N of M" note instead of clipping.
 */
@Component
class TeacherPerformanceRenderer(
    private val properties: ReportProperties = ReportProperties(),
) {

    fun render(spec: TeacherPerformanceSpec): ByteArray {
        return PDDocument().use { document ->
            document.documentInformation.title = spec.title
            val theme = ReportTheme(document)
            val logo = ReportImages.brandingLogo(document, spec.branding.logoUrl, properties.embedLogo)
            ReportCanvas(document, theme, 841.89f, 595.28f).use { canvas ->
                draw(canvas, spec, logo)
            }
            val out = ByteArrayOutputStream()
            document.save(out)
            out.toByteArray()
        }
    }

    private fun draw(canvas: ReportCanvas, spec: TeacherPerformanceSpec, logo: PDImageXObject?) {
        val termLabel = prettyTerm(spec.term)
        val yearLabel = spec.year?.toString().orEmpty()
        ReportFurniture.cardWatermark(canvas, spec.branding)
        val offset = ReportFurniture.cardHeader(
            canvas,
            spec.branding,
            logo,
            yearLabel,
            termLabel,
            "Teacher Performance Report",
            listOf(spec.teacherName, spec.className, listOf(termLabel, yearLabel).filter { it.isNotBlank() }.joinToString(" "))
                .filter { it.isNotBlank() }
                .joinToString("  ·  "),
            landscape = true,
        )

        val contentWidth = canvas.width - 72f
        val subjectsTaught = spec.subjects.size
        val topSubject = spec.topSubject ?: "-"
        val topCaption = if (spec.topSubject == null) "No subject assignments recorded" else "Highest average (" + ReportTheme.pct(spec.topSubjectAverage) + ")"

        val kpiTop = offset + 6f
        val gap = 12f
        val kpiWidth = (contentWidth - gap * 3) / 4f
        val kpiHeight = 68f
        kpi(canvas, 36f, canvas.top(kpiTop + kpiHeight), kpiWidth, kpiHeight,
            "SUBJECTS TAUGHT", subjectsTaught.toString(), "In " + spec.className, ReportTheme.TEAL, 1)
        kpi(canvas, 36f + (kpiWidth + gap), canvas.top(kpiTop + kpiHeight), kpiWidth, kpiHeight,
            "LEARNERS", spec.learners.toString(), "Taught this term", ReportTheme.NAVY_SOFT, 2)
        kpi(canvas, 36f + (kpiWidth + gap) * 2, canvas.top(kpiTop + kpiHeight), kpiWidth, kpiHeight,
            "AVERAGE SCORE", ReportTheme.pct(spec.teacherAverage), "Across taught subjects", ReportTheme.TEAL, 0)
        kpi(canvas, 36f + (kpiWidth + gap) * 3, canvas.top(kpiTop + kpiHeight), kpiWidth, kpiHeight,
            "TOP SUBJECT", topSubject, topCaption, ReportTheme.GOLD, 2)

        val panelsTop = kpiTop + kpiHeight + 16f
        val leftWidth = 470f
        val available = canvas.height - 58f - 126f - 14f
        val layout = chooseLayout(spec.subjects, panelsTop, available)

        ReportFurniture.eyebrow(canvas, "Subject delivery", 36f, panelsTop)
        val shownSubjects = spec.subjects.take(layout.subjectsShown)
        if (shownSubjects.isEmpty()) {
            canvas.text("No subject assignments recorded for this class this term.", 36f, canvas.top(panelsTop + 34f), canvas.theme.sans, 9f, ReportTheme.MUTED)
        }
        shownSubjects.forEachIndexed { index, subject ->
            val rowTop = panelsTop + 16f + index * layout.rowHeight
            val baseline = canvas.top(rowTop + 14f)
            canvas.text(canvas.ellipsis(subject.subject, canvas.theme.sansBold, 9.2f, leftWidth - 80f), 36f, baseline, canvas.theme.sansBold, 9.2f, ReportTheme.INK)
            ReportFurniture.cardChipRight(canvas, levelFor(subject.average), 36f + leftWidth, canvas.top(rowTop + 13f) + layout.rowHeight / 2f - 6f)
            canvas.text(
                if (subject.learners == 1) "1 learner" else subject.learners.toString() + " learners",
                36f,
                baseline - 15f,
                canvas.theme.sans,
                6.6f,
                ReportTheme.MUTED,
            )
            canvas.textR(ReportTheme.pct(subject.average), 36f + leftWidth, baseline - 15f, canvas.theme.sansBold, 8.6f, ReportTheme.NAVY)
            bar(canvas, 36f, canvas.top(rowTop + layout.rowHeight - 6f), leftWidth, 5f, subject.average / 100.0, ReportTheme.TEAL)
        }
        if (layout.subjectsShown < spec.subjects.size) {
            canvas.text(
                "Showing " + layout.subjectsShown + " of " + spec.subjects.size + " subjects",
                36f,
                canvas.top(panelsTop + 16f + layout.subjectsShown * layout.rowHeight + 8f),
                canvas.theme.sans,
                6.4f,
                ReportTheme.MUTED,
            )
        }
        val deliveryBottom = panelsTop + 16f + shownSubjects.size * layout.rowHeight + if (layout.subjectsShown < spec.subjects.size) 12f else 0f

        val rightX = 36f + leftWidth + 18f
        val rightWidth = canvas.width - 36f - rightX
        ReportFurniture.eyebrow(canvas, "Strand impact by subject", rightX, panelsTop)
        var strandY = panelsTop + 16f
        if (shownSubjects.none { it.strands.isNotEmpty() }) {
            canvas.text("No strand scores recorded for the taught subjects.", rightX, canvas.top(strandY + 18f), canvas.theme.sans, 8f, ReportTheme.MUTED)
            strandY += 34f
        }
        shownSubjects.forEach { subject ->
            if (subject.strands.isEmpty()) return@forEach
            canvas.tracked(subject.subject.uppercase(), rightX, canvas.top(strandY + 10f), canvas.theme.sansBold, 6.2f, 1.1f, ReportTheme.MUTED)
            strandY += 16f
            subject.strands.take(layout.strandsShown).forEach { strand ->
                strandRow(canvas, strand, rightX, rightWidth, strandY, layout.strandStep)
                strandY += layout.strandStep
            }
            if (subject.strands.size > layout.strandsShown) {
                canvas.text("+" + (subject.strands.size - layout.strandsShown) + " more", rightX, canvas.top(strandY + 6f), canvas.theme.sans, 6f, ReportTheme.MUTED)
                strandY += 8f
            }
            strandY += layout.subjectGap
        }
        val impactBottom = strandY

        val panelsBottom = maxOf(deliveryBottom, impactBottom)
        val stripTop = panelsBottom + 14f
        val stripHeight = 50f
        statStrip(
            canvas, 36f, canvas.top(stripTop + stripHeight), contentWidth, stripHeight,
            listOf(
                "TEACHER AVERAGE" to ReportTheme.pct(spec.teacherAverage),
                "CLASS AVERAGE" to ReportTheme.pct(spec.classAverage),
                "COVERAGE" to (subjectsTaught.toString() + " of " + spec.subjectsInClass + " subjects"),
                "LEARNERS" to spec.learners.toString(),
            ),
        )

        val boxTop = stripTop + stripHeight + 14f
        val boxHeight = 62f
        val fy = canvas.top(boxTop + boxHeight)
        val focusWidth = contentWidth - 226f
        canvas.box(36f, fy, focusWidth, boxHeight, 8f, ReportTheme.MINT_SOFT, ReportTheme.TEAL, 0.8f)
        canvas.tracked("NEXT STEP", 54f, fy + boxHeight - 17f, canvas.theme.sansBold, 5.9f, 1.3f, ReportTheme.TEAL_DARK)
        val weakestSubject = shownSubjects.minByOrNull { it.average }
        val weakStrand = weakestSubject?.strands?.minByOrNull { it.percentage }
        if (weakestSubject == null) {
            canvas.text("No subject assignments recorded for this class this term.", 54f, fy + boxHeight - 37f, canvas.theme.sansBold, 10f, ReportTheme.NAVY)
            canvas.text("Check the subject-teacher assignments with the ICT administrator.", 54f, fy + 19f, canvas.theme.sans, 8.4f, ReportTheme.MUTED)
        } else if (weakStrand == null) {
            canvas.text(canvas.ellipsis(weakestSubject.subject + " is the lowest-taught subject at " + ReportTheme.pct(weakestSubject.average), canvas.theme.sansBold, 10f, focusWidth - 60f), 54f, fy + boxHeight - 37f, canvas.theme.sansBold, 10f, ReportTheme.NAVY)
            canvas.text("Review the subject plan with the class teacher and subject head.", 54f, fy + 19f, canvas.theme.sans, 8.4f, ReportTheme.MUTED)
        } else {
            canvas.text(
                canvas.ellipsis(weakStrand.code + " in " + weakestSubject.subject + " sits at " + ReportTheme.pct(weakStrand.percentage), canvas.theme.sansBold, 10f, focusWidth - 60f),
                54f, fy + boxHeight - 37f, canvas.theme.sansBold, 10f, ReportTheme.NAVY,
            )
            canvas.text("Add weekly reasoning tasks and model worked examples for this strand.", 54f, fy + 19f, canvas.theme.sans, 8.4f, ReportTheme.MUTED)
        }
        val below = shownSubjects.flatMap { it.strands }.count { it.percentage < 70.0 }
        if (below > 0) {
            ReportFurniture.cardChipRight(
                canvas,
                if (below == 1) "1 STRAND BELOW 70%" else below.toString() + " STRANDS BELOW 70%",
                36f + focusWidth - 18f,
                fy + boxHeight / 2f,
            )
        }

        // D5: the signatory is the Exam Office stamp, never a personal name.
        ReportFurniture.horizontalStamp(canvas, spec.branding, 36f + contentWidth - 118f, fy + 18f)
        canvas.trackedR("OFFICIAL SCHOOL RECORD", 36f + contentWidth, fy + 54f, canvas.theme.sansBold, 5.4f, 1f, ReportTheme.MUTED)
        ReportFurniture.cardFooter(canvas, spec.branding, termLabel + " " + yearLabel, 1, 1)
    }

    // ------------------------------------------------------------- layout

    private data class Layout(
        val rowHeight: Float,
        val strandStep: Float,
        val subjectGap: Float,
        val subjectsShown: Int,
        val strandsShown: Int,
    )

    /**
     * Fits the two panels above the fixed bottom block. Tries the v1 geometry first, then
     * compresses, then truncates the strand list and finally the subject list - every
     * truncation is labelled in the document so no number silently disappears.
     */
    private fun chooseLayout(subjects: List<TeacherSubjectPerformance>, panelsTop: Float, available: Float): Layout {
        val geometries = listOf(
            Triple(46f, 19f, 6f),
            Triple(42f, 17f, 5f),
            Triple(38f, 16f, 4f),
            Triple(34f, 14f, 3f),
            Triple(30f, 13f, 2f),
        )
        val strandLimits = listOf(6, 4, 3, 2, 1)
        val subjectLimits = listOf(subjects.size, 5, 4, 3, 2)
        for ((rowHeight, strandStep, subjectGap) in geometries) {
            for (strandLimit in strandLimits) {
                for (subjectLimit in subjectLimits) {
                    if (subjectLimit <= 0) continue
                    val shown = subjects.take(subjectLimit)
                    val delivery = 16f + shown.size * rowHeight
                    var impact = 16f
                    shown.forEach { subject ->
                        if (subject.strands.isNotEmpty()) {
                            impact += 16f + minOf(subject.strands.size, strandLimit) * strandStep + subjectGap
                        }
                    }
                    if (panelsTop + maxOf(delivery, impact) <= available) {
                        return Layout(rowHeight, strandStep, subjectGap, shown.size, strandLimit)
                    }
                }
            }
        }
        val last = geometries.last()
        return Layout(last.first, last.second, last.third, minOf(subjects.size, 2), 1)
    }

    // ------------------------------------------------------------- primitives

    private fun kpi(
        canvas: ReportCanvas,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        label: String,
        value: String,
        caption: String,
        accent: Color,
        icon: Int,
    ) {
        canvas.box(x, y, width, height, 8f, ReportTheme.PAPER, ReportTheme.LINE, 0.8f)
        canvas.fillInk(ReportTheme.mix(accent, ReportTheme.PAPER, 0.86f))
        canvas.circle(x + 30f, y + height / 2f, 16f)
        canvas.stream.fill()
        icon(canvas, icon, x + 30f, y + height / 2f, accent)
        canvas.tracked(label, x + 52f, y + height - 17f, canvas.theme.sansBold, 5.6f, 1.1f, ReportTheme.MUTED)
        val size = if (canvas.textWidth(value, canvas.theme.sansBlack, 20f) > width - 62f) 13f else 20f
        canvas.text(canvas.ellipsis(value, canvas.theme.sansBlack, size, width - 62f), x + 52f, y + height - 38f, canvas.theme.sansBlack, size, ReportTheme.NAVY)
        canvas.text(canvas.ellipsis(caption, canvas.theme.sans, 6.6f, width - 60f), x + 52f, y + height - 52f, canvas.theme.sans, 6.6f, ReportTheme.MUTED)
    }

    private fun icon(canvas: ReportCanvas, kind: Int, cx: Float, cy: Float, ink: Color) {
        canvas.fillInk(ink)
        when (kind) {
            0 -> {
                canvas.fillRect(cx - 7f, cy - 8f, 3.6f, 7f, ink)
                canvas.fillRect(cx - 1.8f, cy - 8f, 3.6f, 11f, ink)
                canvas.fillRect(cx + 3.4f, cy - 8f, 3.6f, 15f, ink)
            }
            1 -> for (i in 0 until 3) canvas.line(cx - 7f, cy + 5f - i * 5f, cx + 7f - i * 2f, cy + 5f - i * 5f, ink, 1.4f)
            2 -> {
                canvas.strokeInk(ink, 1.8f)
                canvas.stream.moveTo(cx - 7f, cy)
                canvas.stream.lineTo(cx - 2f, cy - 6f)
                canvas.stream.lineTo(cx + 8f, cy + 7f)
                canvas.stream.stroke()
            }
            else -> {
                canvas.line(cx, cy + 6f, cx, cy - 3f, ink, 2f)
                canvas.fillInk(ink)
                canvas.circle(cx, cy - 7f, 1.6f)
                canvas.stream.fill()
            }
        }
    }

    private fun strandRow(canvas: ReportCanvas, strand: TeacherStrandScore, x: Float, width: Float, rowTop: Float, step: Float) {
        val baseline = canvas.top(rowTop + 10f)
        canvas.text(canvas.ellipsis(strand.code, canvas.theme.sansBold, 7.2f, 54f), x, baseline, canvas.theme.sansBold, 7.2f, ReportTheme.INK)
        bar(canvas, x + 58f, canvas.top(rowTop + 9f), width - 116f, 5.5f, strand.percentage / 100.0, if (strand.percentage < 70.0) ReportTheme.AMBER else ReportTheme.TEAL)
        canvas.textR(
            ReportTheme.pct(strand.percentage),
            x + width,
            baseline,
            canvas.theme.sansBold,
            7.4f,
            if (strand.percentage < 70.0) ReportTheme.AMBER else ReportTheme.NAVY,
        )
    }

    private fun statStrip(canvas: ReportCanvas, x: Float, y: Float, width: Float, height: Float, cells: List<Pair<String, String>>) {
        canvas.box(x, y, width, height, 6f, ReportTheme.PAPER, ReportTheme.LINE, 0.8f)
        val cellWidth = width / cells.size
        cells.forEachIndexed { index, (label, value) ->
            val cellX = x + index * cellWidth
            if (index > 0) canvas.line(cellX, y + 9f, cellX, y + height - 9f, ReportTheme.LINE, 0.6f)
            canvas.tracked(label, cellX + 16f, y + height - 16f, canvas.theme.sansBold, 5.8f, 1.3f, ReportTheme.TEAL_DARK)
            canvas.text(canvas.ellipsis(value, canvas.theme.sansBlack, 13f, cellWidth - 30f), cellX + 16f, y + 14f, canvas.theme.sansBlack, 13f, ReportTheme.NAVY)
        }
    }

    private fun bar(canvas: ReportCanvas, x: Float, y: Float, width: Float, height: Float, fraction: Double, fill: Color) {
        canvas.fillRound(x, y, width, height, height / 2f, ReportTheme.mix(ReportTheme.LINE, ReportTheme.PAPER, 0.35f))
        val filled = (width * fraction.coerceIn(0.0, 1.0)).toFloat().coerceAtLeast(if (fraction > 0) height else 0f)
        if (filled > 0f) canvas.fillRound(x, y, filled, height, height / 2f, fill)
    }

    /**
     * Level for the subject-delivery chip. CBC reports store word ratings per learning area, not a
     * percentage level, so this is a documented derived descriptor over the v1 percentage bands
     * (>=80 EE, >=65 ME, >=50 AE, >=35 BE, else DE) - the same language the learner cards use.
     */
    private fun levelFor(percentage: Double): String = when {
        percentage >= 80.0 -> "EE"
        percentage >= 65.0 -> "ME"
        percentage >= 50.0 -> "AE"
        percentage >= 35.0 -> "BE"
        else -> "DE"
    }

    private fun prettyTerm(term: String): String {
        val trimmed = term.trim()
        if (trimmed.uppercase().startsWith("TERM_")) return "Term " + trimmed.substringAfter("TERM_").trim()
        return trimmed
    }
}
