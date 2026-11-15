package com.afrithecus.brainbox.api.report.render

import com.afrithecus.brainbox.api.cbcratings.web.CbcClassReportPayload
import com.afrithecus.brainbox.api.report.CbcClassSpec
import com.afrithecus.brainbox.api.report.ReportBranding
import com.afrithecus.brainbox.api.report.ReportProperties
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject
import org.springframework.stereotype.Component
import java.io.ByteArrayOutputStream

/**
 * CBC_CLASS report (plan A5, type 3, v1 page 03).
 *
 * One A4 landscape page: hero header, four KPI tiles (overall average, strands assessed,
 * on track, needs support - with the real roster count from D2), strand mastery bars with
 * On Track / Needs Support chips, subject and teacher impact bars, and the "focus for next
 * term" callout built from the weakest strands. Teacher performance (A6) is a different
 * report and never renders through this class-level view.
 */
@Component
class CbcClassRenderer(
    private val properties: ReportProperties = ReportProperties(),
) {

    fun render(spec: CbcClassSpec): ByteArray {
        require(spec.focusTeacher == null) { "Teacher performance reports are not class reports" }
        return PDDocument().use { document ->
            document.documentInformation.title = spec.title
            val theme = ReportTheme(document)
            val logo = ReportImages.brandingLogo(document, spec.branding.logoUrl, properties.embedLogo)
            ReportCanvas(document, theme, 841.89f, 595.28f).use { canvas ->
                draw(canvas, spec.branding, logo, spec.classReport, spec.year?.toString().orEmpty())
            }
            val out = ByteArrayOutputStream()
            document.save(out)
            out.toByteArray()
        }
    }

    private fun draw(canvas: ReportCanvas, branding: ReportBranding, logo: PDImageXObject?, report: CbcClassReportPayload, specYear: String) {
        val termLabel = prettyTerm(report.term)
        ReportFurniture.cardWatermark(canvas, branding)
        val offset = ReportFurniture.cardHeader(
            canvas,
            branding,
            logo,
            specYear,
            termLabel,
            "Class CBC Report",
            report.className + "  -  " + termLabel,
            landscape = true,
        )
        val contentWidth = canvas.width - 72f
        val onTrack = report.strandMastery.count { !it.isWeakStrand }
        val needsSupport = report.strandMastery.size - onTrack

        val kpiTop = offset + 6f
        val gap = 12f
        val kpiWidth = (contentWidth - gap * 3) / 4f
        val kpiHeight = 68f
        val learners = if (report.studentCount > 0) report.studentCount else report.subjectTeacherPerformance.maxOfOrNull { it.studentCount } ?: 0
        kpi(
            canvas, 36f, canvas.top(kpiTop + kpiHeight), kpiWidth, kpiHeight,
            "OVERALL AVERAGE", ReportTheme.pct(report.overallClassAverage),
            "Across " + report.strandMastery.size + " learning areas", ReportTheme.TEAL, 0,
        )
        kpi(
            canvas, 36f + (kpiWidth + gap), canvas.top(kpiTop + kpiHeight), kpiWidth, kpiHeight,
            "STRANDS ASSESSED", report.strandMastery.size.toString(),
            "CBC learning areas this term", ReportTheme.NAVY_SOFT, 1,
        )
        kpi(
            canvas, 36f + (kpiWidth + gap) * 2, canvas.top(kpiTop + kpiHeight), kpiWidth, kpiHeight,
            "ON TRACK", onTrack.toString(),
            "Meeting or exceeding expectations", ReportTheme.TEAL, 2,
        )
        kpi(
            canvas, 36f + (kpiWidth + gap) * 3, canvas.top(kpiTop + kpiHeight), kpiWidth, kpiHeight,
            "NEEDS SUPPORT", needsSupport.toString(),
            when {
                learners <= 0 -> "Watch list this term"
                learners == 1 -> "1 learner in class"
                else -> learners.toString() + " learners in class"
            },
            if (needsSupport > 0) ReportTheme.RED else ReportTheme.MUTED, 3,
        )

        val panelsTop = kpiTop + kpiHeight + 16f
        val leftWidth = 470f
        ReportFurniture.eyebrow(canvas, "Strand mastery", 36f, panelsTop)
        val rowHeight = 26f
        if (report.strandMastery.isEmpty()) {
            canvas.text("No strand ratings recorded for this class this term.", 36f, canvas.top(panelsTop + 30f), canvas.theme.sans, 9f, ReportTheme.MUTED)
        }
        report.strandMastery.forEachIndexed { index, strand ->
            val rowTop = panelsTop + 16f + index * rowHeight
            val baseline = canvas.top(rowTop + rowHeight) + 9f
            val weak = strand.isWeakStrand
            canvas.text(canvas.ellipsis(strand.strandName, canvas.theme.sans, 8.2f, 170f), 36f, baseline, canvas.theme.sans, 8.2f, ReportTheme.INK)
            bar(canvas, 36f + 182f, baseline - 2f, 132f, 6f, strand.classAverage / 100.0, if (weak) ReportTheme.AMBER else ReportTheme.TEAL)
            canvas.textR(ReportTheme.pct(strand.classAverage), 36f + leftWidth - 116f, baseline, canvas.theme.sansBold, 8.4f, if (weak) ReportTheme.AMBER else ReportTheme.NAVY)
            val status = if (weak) "Needs Support" else "On Track"
            ReportFurniture.cardChip(
                canvas,
                status,
                36f + leftWidth - 34f,
                canvas.top(rowTop + rowHeight) + rowHeight / 2f,
            )
        }

        val rightX = 36f + leftWidth + 18f
        val rightWidth = canvas.width - 36f - rightX
        ReportFurniture.eyebrow(canvas, "Subject & teacher impact", rightX, panelsTop)
        val impactHeight = 36f
        report.subjectTeacherPerformance.forEachIndexed { index, impact ->
            val rowTop = panelsTop + 16f + index * impactHeight
            val baseline = canvas.top(rowTop + 14f)
            canvas.text(canvas.ellipsis(impact.subject, canvas.theme.sans, 8.2f, rightWidth - 60f), rightX, baseline, canvas.theme.sans, 8.2f, ReportTheme.INK)
            canvas.textR(ReportTheme.pct(impact.classAverage), rightX + rightWidth, baseline, canvas.theme.sansBold, 8.4f, ReportTheme.NAVY)
            bar(canvas, rightX, baseline - 8f, rightWidth, 5.5f, impact.classAverage / 100.0, ReportTheme.TEAL)
            val teacher = impact.teacherName.ifBlank { "Unassigned" }
            val learnerLabel = if (impact.studentCount == 1) "1 learner" else impact.studentCount.toString() + " learners"
            canvas.text(
                canvas.ellipsis(teacher + "  -  " + learnerLabel, canvas.theme.sans, 6.4f, rightWidth),
                rightX,
                baseline - 16f,
                canvas.theme.sans,
                6.4f,
                ReportTheme.MUTED,
            )
        }

        val panelsBottom = panelsTop + 16f + maxOf(report.strandMastery.size * rowHeight, report.subjectTeacherPerformance.size * impactHeight)
        val focusTop = panelsBottom + 18f
        val focusHeight = 62f
        val focusY = canvas.top(focusTop + focusHeight)
        canvas.box(36f, focusY, contentWidth, focusHeight, 8f, ReportTheme.MINT_SOFT, ReportTheme.TEAL, 0.8f)
        canvas.tracked("FOCUS FOR NEXT TERM", 54f, focusY + focusHeight - 17f, canvas.theme.sansBold, 5.9f, 1.3f, ReportTheme.TEAL_DARK)
        val weakStrands = report.strandMastery.filter { it.isWeakStrand }
        if (weakStrands.isEmpty()) {
            canvas.text("Every strand is on track - keep the current learning plan.", 54f, focusY + 20f, canvas.theme.sansBold, 9.6f, ReportTheme.NAVY)
        } else {
            canvas.text(
                canvas.ellipsis(weakStrands.joinToString(", ") { it.strandName }, canvas.theme.sansBold, 10f, contentWidth - 280f),
                54f,
                focusY + focusHeight - 36f,
                canvas.theme.sansBold,
                10f,
                ReportTheme.NAVY,
            )
            canvas.text(
                canvas.ellipsis(weakStrands.first().recommendation ?: "Plan targeted support for this learning area.", canvas.theme.sans, 8.4f, contentWidth - 280f),
                54f,
                focusY + 19f,
                canvas.theme.sans,
                8.4f,
                ReportTheme.MUTED,
            )
        }
        ReportFurniture.cardChipRight(
            canvas,
            if (needsSupport == 1) "1 STRAND BELOW TARGET" else needsSupport.toString() + " STRANDS BELOW TARGET",
            36f + contentWidth - 18f,
            focusY + focusHeight / 2f,
        )
        ReportFurniture.cardFooter(canvas, branding, termLabel, 1, 1)
    }

    private fun kpi(
        canvas: ReportCanvas,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        label: String,
        value: String,
        caption: String,
        accent: java.awt.Color,
        icon: Int,
    ) {
        canvas.box(x, y, width, height, 8f, ReportTheme.PAPER, ReportTheme.LINE, 0.8f)
        canvas.fillInk(ReportTheme.mix(accent, ReportTheme.PAPER, 0.86f))
        canvas.circle(x + 30f, y + height / 2f, 16f)
        canvas.stream.fill()
        icon(canvas, icon, x + 30f, y + height / 2f, accent)
        canvas.tracked(label, x + 52f, y + height - 17f, canvas.theme.sansBold, 5.6f, 1.1f, ReportTheme.MUTED)
        val size = if (canvas.textWidth(value, canvas.theme.sansBlack, 20f) > width - 62f) 13f else 20f
        canvas.text(value, x + 52f, y + height - 38f, canvas.theme.sansBlack, size, ReportTheme.NAVY)
        canvas.text(canvas.ellipsis(caption, canvas.theme.sans, 6.6f, width - 60f), x + 52f, y + height - 52f, canvas.theme.sans, 6.6f, ReportTheme.MUTED)
    }

    private fun icon(canvas: ReportCanvas, kind: Int, cx: Float, cy: Float, ink: java.awt.Color) {
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

    private fun bar(canvas: ReportCanvas, x: Float, y: Float, width: Float, height: Float, fraction: Double, fill: java.awt.Color) {
        canvas.fillRound(x, y, width, height, height / 2f, ReportTheme.mix(ReportTheme.LINE, ReportTheme.PAPER, 0.35f))
        val filled = (width * fraction.coerceIn(0.0, 1.0)).toFloat().coerceAtLeast(if (fraction > 0) height else 0f)
        if (filled > 0f) canvas.fillRound(x, y, filled, height, height / 2f, fill)
    }

    private fun prettyTerm(term: String): String {
        val trimmed = term.trim()
        if (trimmed.uppercase().startsWith("TERM_")) return "Term " + trimmed.substringAfter("TERM_").trim()
        return trimmed
    }
}
