package com.afrithecus.brainbox.api.report.render

import com.afrithecus.brainbox.api.report.ReportBranding
import com.afrithecus.brainbox.api.report.ReportProperties
import com.afrithecus.brainbox.api.report.TraditionalStudentsSpec
import com.afrithecus.brainbox.api.traditional.web.TraditionalStudentReportDto
import com.afrithecus.brainbox.api.traditional.web.TraditionalSubjectResultDto
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject
import org.springframework.stereotype.Component
import java.io.ByteArrayOutputStream
import java.util.Locale

/**
 * TRADITIONAL_STUDENT card (plan A3, type 1, v1 page 01).
 *
 * One A4 portrait card per learner: light identity header, meta strip, academic
 * performance table (subject / percentage / level chip / teacher note), overall
 * achievement + class position cards, class-teacher remarks with signature, the
 * closing line, the grading key and the wave footer. Row heights compress when a card
 * has many subjects so a 9+ subject exam still fits one page.
 */
@Component
class TraditionalStudentRenderer(
    private val properties: ReportProperties = ReportProperties(),
) {

    fun render(spec: TraditionalStudentsSpec): ByteArray {
        require(spec.reports.isNotEmpty()) { "Traditional student report needs at least one learner" }
        return PDDocument().use { document ->
            document.documentInformation.title = spec.title
            val theme = ReportTheme(document)
            val logo = ReportImages.brandingLogo(document, spec.branding.logoUrl, properties.embedLogo)
            spec.reports.forEachIndexed { index, report ->
                ReportCanvas(document, theme, 595.28f, 841.89f).use { canvas ->
                    drawCard(canvas, spec.branding, logo, report, spec.bands, index + 1, spec.reports.size)
                }
            }
            val out = ByteArrayOutputStream()
            document.save(out)
            out.toByteArray()
        }
    }

    private fun drawCard(
        canvas: ReportCanvas,
        branding: ReportBranding,
        logo: PDImageXObject?,
        report: TraditionalStudentReportDto,
        bands: List<com.afrithecus.brainbox.api.report.ReportGradingBand>,
        page: Int,
        pages: Int,
    ) {
        ReportFurniture.cardWatermark(canvas, branding)
        val termLabel = report.term + " " + report.year
        val offset = ReportFurniture.cardHeader(
            canvas,
            branding,
            logo,
            report.year.toString(),
            report.term,
            "Term Report Card",
            "End of term assessment  -  " + termLabel,
        )
        ReportFurniture.metaStrip(
            canvas,
            offset + 4f,
            listOf(
                "Learner" to report.studentName,
                "Class" to report.classTag,
                "Admission No." to report.admissionNumber,
                "Position" to (report.classPosition.toString() + " of " + report.totalStudentsInClass),
            ),
            listOf(1.55f, 1.0f, 0.85f, 0.75f),
        )

        val subjects = report.subjectResults
        val tableTop = offset + 42f + 26f
        ReportFurniture.eyebrow(canvas, "Academic performance", 38f, tableTop - 9f)
        val compact = subjects.size > 9
        val fixed = 148f + 4f + 42f + 26f + 24f + 14f + 112f + 14f + 98f + 14f + (if (compact) 0f else 26f) + 30f + 18f + 50f
        val rowHeight = ((canvas.height - fixed) / subjects.size.coerceAtLeast(1)).coerceIn(14f, 29f)
        drawSubjectTable(canvas, subjects, tableTop, rowHeight)

        val cardsTop = tableTop + 24f + subjects.size * rowHeight + 14f
        summaryCards(canvas, report, cardsTop)
        remarksPanel(canvas, report, cardsTop + 112f + 14f)
        var cursor = cardsTop + 112f + 14f + 98f + 26f
        if (!compact) {
            closingQuote(canvas, branding, report, cursor)
            cursor += 30f
        }
        gradingKey(canvas, cursor, bands)
        officialRecord(canvas, cursor + 18f)
        ReportFurniture.cardFooter(canvas, branding, termLabel, page, pages)
    }

    private fun drawSubjectTable(canvas: ReportCanvas, subjects: List<TraditionalSubjectResultDto>, top: Float, rowHeight: Float) {
        val x = 36f
        val width = canvas.width - 72f
        val noteWidth = width - 26f - 150f - 64f - 58f
        val widths = floatArrayOf(26f, 150f, 64f, 58f, noteWidth)
        val headerHeight = 24f
        canvas.fillRound(x, canvas.top(top + headerHeight), width, headerHeight, 3f, ReportTheme.NAVY)
        headerCell(canvas, "#", x + 10f, top, headerHeight)
        headerCell(canvas, "SUBJECT", x + 26f + 10f, top, headerHeight)
        headerCellRight(canvas, "SCORE", x + 26f + 150f + 64f - 14f, top, headerHeight)
        headerCellCentre(canvas, "LEVEL", x + 26f + 150f + 64f + 29f, top, headerHeight)
        headerCell(canvas, "TEACHER'S NOTE", x + 26f + 150f + 64f + 58f + 10f, top, headerHeight)

        subjects.forEachIndexed { index, subject ->
            val rowTop = top + headerHeight + index * rowHeight
            val rowY = canvas.top(rowTop + rowHeight)
            if (index % 2 == 1) canvas.fillRect(x, rowY, width, rowHeight, ReportTheme.MINT_SOFT)
            canvas.line(x, rowY, x + width, rowY, ReportTheme.LINE, 0.45f)
            val centreY = canvas.top(rowTop + rowHeight / 2f)
            val baseline = centreY - 0.36f * 9.4f
            canvas.textC((index + 1).toString(), x + widths[0] / 2f, baseline, canvas.theme.sans, 8f, ReportTheme.MUTED)
            canvas.text(
                canvas.ellipsis(subject.subjectName, canvas.theme.sansBold, 9.4f, widths[1] - 18f),
                x + widths[0] + 10f,
                baseline,
                canvas.theme.sansBold,
                9.4f,
                ReportTheme.INK,
            )
            canvas.textR(
                String.format(Locale.US, "%.0f%%", subject.percentage),
                x + widths[0] + widths[1] + widths[2] - 14f,
                centreY - 0.36f * 10f,
                canvas.theme.sansBlack,
                10f,
                ReportTheme.NAVY,
            )
            ReportFurniture.cardChip(canvas, subject.grade, x + widths[0] + widths[1] + widths[2] + widths[3] / 2f, centreY)
            canvas.text(
                canvas.ellipsis(noteFor(subject.grade), canvas.theme.sans, 8.4f, noteWidth - 14f),
                x + widths[0] + widths[1] + widths[2] + widths[3] + 10f,
                baseline,
                canvas.theme.sans,
                8.4f,
                ReportTheme.MUTED,
            )
        }
        canvas.strokeRound(
            x,
            canvas.top(top + headerHeight + subjects.size * rowHeight),
            width,
            headerHeight + subjects.size * rowHeight,
            3f,
            ReportTheme.LINE,
            0.7f,
        )
    }

    private fun headerCell(canvas: ReportCanvas, label: String, x: Float, top: Float, height: Float) {
        val baseline = canvas.top(top + height / 2f) - 0.36f * 6.4f
        canvas.tracked(label, x, baseline, canvas.theme.sansBold, 6.4f, 0.8f, ReportTheme.PAPER)
    }

    private fun headerCellRight(canvas: ReportCanvas, label: String, rightX: Float, top: Float, height: Float) {
        val baseline = canvas.top(top + height / 2f) - 0.36f * 6.4f
        canvas.trackedR(label, rightX, baseline, canvas.theme.sansBold, 6.4f, 0.8f, ReportTheme.PAPER)
    }

    private fun headerCellCentre(canvas: ReportCanvas, label: String, centreX: Float, top: Float, height: Float) {
        val baseline = canvas.top(top + height / 2f) - 0.36f * 6.4f
        canvas.trackedC(label, centreX, baseline, canvas.theme.sansBold, 6.4f, 0.8f, ReportTheme.PAPER)
    }

    private fun noteFor(grade: String): String = when (grade.trim().uppercase()) {
        "EE" -> "Secure and consistent across tasks"
        "ME" -> "On track for this term's targets"
        "AE" -> "Approaching; extra practice needed"
        "BE" -> "Below expectations; support required"
        else -> "Developing; targeted help planned"
    }

    private fun summaryCards(canvas: ReportCanvas, report: TraditionalStudentReportDto, top: Float) {
        val x = 36f
        val gap = 14f
        val width = canvas.width - 72f
        val cardWidth = (width - gap) / 2f
        val height = 112f
        val y = canvas.top(top + height)

        canvas.box(x, y, cardWidth, height, 8f, ReportTheme.MINT, ReportTheme.TEAL, 0.8f)
        canvas.tracked("OVERALL ACHIEVEMENT", x + 16f, y + height - 16f, canvas.theme.sansBold, 5.8f, 1.3f, ReportTheme.TEAL_DARK)
        canvas.text(ReportTheme.pct(report.overallPercentage), x + 16f, y + height - 52f, canvas.theme.sansBlack, 29f, ReportTheme.NAVY)
        val stars = ReportTheme.levelStars(report.overallGrade)
        if (stars > 0) canvas.stars(x + 19f, y + height - 68f, stars, 3.8f, 2.4f, ReportTheme.GOLD)
        val maxMarks = report.subjectResults.sumOf { it.maxScore }
        canvas.text(
            "Across " + report.subjectResults.size + " subjects  -  " + report.totalScore + " of " + maxMarks + " marks",
            x + 16f,
            y + 14f,
            canvas.theme.sans,
            7.2f,
            ReportTheme.MUTED,
        )
        ReportFurniture.ribbon(canvas, levelLabel(report.overallGrade), x + cardWidth - 62f, y + height - 52f, ReportTheme.TEAL, ReportTheme.PAPER)

        val x2 = x + cardWidth + gap
        canvas.box(x2, y, cardWidth, height, 8f, ReportTheme.GOLD_SOFT, ReportTheme.GOLD, 0.7f)
        canvas.tracked("CLASS POSITION", x2 + 16f, y + height - 16f, canvas.theme.sansBold, 5.8f, 1.3f, ReportTheme.AMBER)
        val position = report.classPosition.toString()
        canvas.text(position, x2 + 16f, y + height - 52f, canvas.theme.sansBlack, 30f, ReportTheme.NAVY)
        val positionWidth = canvas.textWidth(position, canvas.theme.sansBlack, 30f)
        canvas.text("of " + report.totalStudentsInClass, x2 + 16f + positionWidth + 7f, y + height - 50f, canvas.theme.sansBold, 9.5f, ReportTheme.MUTED)
        ReportFurniture.medal(canvas, x2 + cardWidth - 46f, y + height - 26f, 15f, ReportTheme.GOLD)
        val topPercent = if (report.totalStudentsInClass <= 0) 100 else (100.0 * report.classPosition / report.totalStudentsInClass).toInt().coerceAtLeast(1)
        ReportFurniture.ribbon(canvas, positionLabel(topPercent), x2 + cardWidth - 58f, y + 30f, ReportTheme.GOLD, ReportTheme.PAPER)
        canvas.text("Top " + topPercent + "% of " + report.classTag, x2 + 16f, y + 14f, canvas.theme.sans, 7.2f, ReportTheme.MUTED)
    }

    private fun remarksPanel(canvas: ReportCanvas, report: TraditionalStudentReportDto, top: Float) {
        val x = 36f
        val width = canvas.width - 72f
        val height = 98f
        val y = canvas.top(top + height)
        canvas.box(x, y, width, height, 8f, ReportTheme.PAPER, ReportTheme.LINE, 0.7f)
        canvas.fillRect(x, y + 10f, 3.4f, height - 20f, ReportTheme.TEAL)
        canvas.tracked("CLASS TEACHER'S REMARKS", x + 18f, y + height - 17f, canvas.theme.sansBold, 5.8f, 1.3f, ReportTheme.NAVY)
        val remarks = report.teacherRemarks?.takeIf { it.isNotBlank() }
        val lines = if (remarks == null) {
            listOf("No remarks recorded for this term.")
        } else {
            canvas.wrap(remarks, canvas.theme.sansItalic, 9.2f, width - 220f)
        }
        var lineY = y + height - 36f
        lines.take(3).forEach { line ->
            canvas.text(line, x + 18f, lineY, canvas.theme.sansItalic, 9.2f, if (remarks == null) ReportTheme.MUTED else ReportTheme.INK)
            lineY -= 12.5f
        }
        val signatureX = x + width - 186f
        ReportFurniture.signature(canvas, signatureX, y + 44f, ReportTheme.NAVY)
        canvas.line(signatureX, y + 40f, x + width - 20f, y + 40f, ReportTheme.LINE, 0.6f)
        canvas.textR(report.classTeacherName ?: "", x + width - 20f, y + 26f, canvas.theme.sansBold, 8.8f, ReportTheme.INK)
        canvas.trackedR("CLASS TEACHER", x + width - 20f, y + 14f, canvas.theme.sansBold, 5.6f, 1.2f, ReportTheme.MUTED)
    }

    private fun closingQuote(canvas: ReportCanvas, branding: ReportBranding, report: TraditionalStudentReportDto, top: Float) {
        val centreY = canvas.top(top)
        val firstName = report.studentName.trim().split(Regex("\\s+")).firstOrNull().orEmpty()
        canvas.textC("Keep shining, " + firstName + " - your consistency is your strength.", canvas.width / 2f, centreY, canvas.theme.serif, 12.4f, ReportTheme.NAVY_SOFT)
        canvas.star(36f + 60f, centreY + 4f, 4f, ReportTheme.GOLD)
        canvas.star(canvas.width - 36f - 60f, centreY + 4f, 4f, ReportTheme.GOLD)
        branding.motto?.takeIf { it.isNotBlank() }?.let {
            canvas.trackedC(it.uppercase(), canvas.width / 2f, centreY - 15f, canvas.theme.sansBold, 6f, 1.6f, ReportTheme.TEAL)
        }
    }

    private fun gradingKey(canvas: ReportCanvas, top: Float, bands: List<com.afrithecus.brainbox.api.report.ReportGradingBand>) {
        val y = canvas.top(top)
        val grades = if (bands.isEmpty()) {
            listOf("EE", "ME", "AE", "BE", "DE")
        } else {
            bands.map { it.label }
        }
        val labels = grades.map { label -> ReportTheme.shortLevel(label).ifBlank { label } }
        var total = 0f
        labels.forEach { total += canvas.textWidth(it, canvas.theme.sans, 6.4f) + 26f }
        var x = (canvas.width - total) / 2f
        grades.forEachIndexed { index, grade ->
            ReportFurniture.cardChip(canvas, grade, x + 9f, y + 2.5f)
            // A single-letter label is its own short name; do not print it twice.
            val label = labels[index].takeIf { it != grade }
            if (label != null) {
                canvas.text(label, x + 22f, y, canvas.theme.sans, 6.4f, ReportTheme.MUTED)
                x += canvas.textWidth(label, canvas.theme.sans, 6.4f) + 26f
            } else {
                x += 26f
            }
        }
    }

    private fun officialRecord(canvas: ReportCanvas, top: Float) {
        val y = canvas.top(top)
        canvas.line(36f + 130f, y + 10f, canvas.width - 36f - 130f, y + 10f, ReportTheme.LINE, 0.5f)
        canvas.textC(
            "Official school record generated from the assessment register - keep this card safe.",
            canvas.width / 2f,
            y,
            canvas.theme.sans,
            6.4f,
            ReportTheme.MUTED,
        )
    }

    /** Session bands may use custom labels; fall back to the label itself when unknown. */
    private fun levelLabel(grade: String): String =
        ReportTheme.shortLevel(grade).ifBlank { grade.trim() }

    private fun positionLabel(topPercent: Int): String = when {
        topPercent <= 10 -> "Outstanding"
        topPercent <= 25 -> "Strong"
        topPercent <= 50 -> "Steady"
        else -> "Building"
    }
}
