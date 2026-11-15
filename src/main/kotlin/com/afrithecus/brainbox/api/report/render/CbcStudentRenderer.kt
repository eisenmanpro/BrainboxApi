package com.afrithecus.brainbox.api.report.render

import com.afrithecus.brainbox.api.cbcratings.web.CbcStrandRatingPayload
import com.afrithecus.brainbox.api.cbcratings.web.PaperExamResultPayload
import com.afrithecus.brainbox.api.report.CbcStudentCard
import com.afrithecus.brainbox.api.report.CbcStudentsSpec
import com.afrithecus.brainbox.api.report.ReportBranding
import com.afrithecus.brainbox.api.report.ReportProperties
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject
import org.springframework.stereotype.Component
import java.io.ByteArrayOutputStream
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max

/**
 * CBC_STUDENT card (plan A4, type 2, v1 page 02, decisions D1/D7).
 *
 * One A4 portrait card per learner: learning areas table with the real learning-area
 * name (never CBC1..7), evidence descriptor, code and level chip; overall achievement +
 * attendance ring; the mandatory paper-exam block carrying **exactly one** exam (the
 * latest PUBLISHED traditional exam in which the learner has marks) with an exam/term/
 * date label and two-column overflow above six subjects; teacher comments; grading key.
 *
 * The block never accumulates earlier exams (decision 2) and has an explicit empty state
 * when the source returns no published marks (D7).
 */
@Component
class CbcStudentRenderer(
    private val properties: ReportProperties = ReportProperties(),
) {

    fun render(spec: CbcStudentsSpec): ByteArray {
        require(spec.cards.isNotEmpty()) { "CBC student report needs at least one learner" }
        return PDDocument().use { document ->
            document.documentInformation.title = spec.title
            val theme = ReportTheme(document)
            val logo = ReportImages.brandingLogo(document, spec.branding.logoUrl, properties.embedLogo)
            spec.cards.forEachIndexed { index, entry ->
                ReportCanvas(document, theme, 595.28f, 841.89f).use { canvas ->
                    drawCard(canvas, spec.branding, logo, entry, index + 1, spec.cards.size)
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
        entry: CbcStudentCard,
        page: Int,
        pages: Int,
    ) {
        val card = entry.card
        ReportFurniture.cardWatermark(canvas, branding)
        val termLabel = prettyTerm(card.term)
        val yearLabel = entry.year?.toString().orEmpty()
        val offset = ReportFurniture.cardHeader(
            canvas,
            branding,
            logo,
            yearLabel,
            termLabel,
            "CBC Report Card",
            "Learning areas assessed  -  " + termLabel + " " + yearLabel,
        )
        ReportFurniture.metaStrip(
            canvas,
            offset + 4f,
            listOf(
                "Learner" to entry.studentName,
                "Grade" to (entry.gradeLevel ?: card.gradeLevel.orEmpty()),
                "Admission No." to card.admissionNumber.orEmpty(),
                "Attendance" to ReportTheme.pct(card.attendancePercentage),
            ),
            listOf(1.55f, 1.0f, 0.85f, 0.75f),
        )

        val ratings = card.strandRatings
        val papers = card.paperExamResults
        val tableTop = offset + 42f + 26f
        ReportFurniture.eyebrow(canvas, "Learning areas assessed", 38f, tableTop - 9f)

        var rowHeight = 30f
        var paperLine = 21f
        var commentsHeight = 112f
        val contentLimit = canvas.height - 58f
        var total = layoutTotal(tableTop, ratings.size, papers.size, rowHeight, paperLine, commentsHeight)
        while (total > contentLimit && rowHeight > 20f) {
            rowHeight -= 1f
            total = layoutTotal(tableTop, ratings.size, papers.size, rowHeight, paperLine, commentsHeight)
        }
        while (total > contentLimit && paperLine > 14f) {
            paperLine -= 1f
            total = layoutTotal(tableTop, ratings.size, papers.size, rowHeight, paperLine, commentsHeight)
        }
        if (total > contentLimit) commentsHeight = 76f

        drawLearningAreas(canvas, ratings, tableTop, rowHeight)
        val cardsTop = tableTop + 24f + ratings.size * rowHeight + 14f
        summaryCards(canvas, entry, cardsTop)
        val bottomTop = cardsTop + 96f + 14f
        var cursor = bottomTop
        cursor = bottomSection(canvas, entry, papers, cursor, paperLine, commentsHeight)
        gradingKey(canvas, cursor + 18f)
        officialRecord(canvas, cursor + 36f)
        ReportFurniture.cardFooter(canvas, branding, termLabel + " " + yearLabel, page, pages)
    }

    /** Total content height (from the table top to the bottom of the comments area). */
    private fun layoutTotal(tableTop: Float, strands: Int, papers: Int, rowHeight: Float, paperLine: Float, commentsHeight: Float): Float {
        val tableEnd = tableTop + 24f + strands * rowHeight
        val cardsTop = tableEnd + 14f
        val bottomTop = cardsTop + 96f + 14f
        val bottom = if (papers <= 6) {
            max(commentsHeight, 40f + papers * paperLine)
        } else {
            val twoColumn = 40f + ceil(papers / 2.0).toFloat() * (paperLine - 3f) + 8f
            twoColumn + 10f + commentsHeight
        }
        return bottomTop + bottom + 56f
    }

    private fun drawLearningAreas(canvas: ReportCanvas, ratings: List<CbcStrandRatingPayload>, top: Float, rowHeight: Float) {
        val x = 36f
        val width = canvas.width - 72f
        val widths = floatArrayOf(24f, width - 24f - 58f - 96f, 58f, 96f)
        val headerHeight = 24f
        canvas.fillRound(x, canvas.top(top + headerHeight), width, headerHeight, 3f, ReportTheme.NAVY)
        val headerBaseline = canvas.top(top + headerHeight / 2f) - 0.36f * 6.2f
        canvas.tracked("#", x + 10f, headerBaseline, canvas.theme.sansBold, 6.2f, 1f, ReportTheme.PAPER)
        canvas.tracked("LEARNING AREA AND EVIDENCE", x + widths[0] + 10f, headerBaseline, canvas.theme.sansBold, 6.2f, 1f, ReportTheme.PAPER)
        canvas.trackedC("CODE", x + widths[0] + widths[1] + widths[2] / 2f, headerBaseline, canvas.theme.sansBold, 6.2f, 1f, ReportTheme.PAPER)
        canvas.trackedC("LEVEL", x + widths[0] + widths[1] + widths[2] + widths[3] / 2f, headerBaseline, canvas.theme.sansBold, 6.2f, 1f, ReportTheme.PAPER)

        ratings.forEachIndexed { index, rating ->
            val rowTop = top + headerHeight + index * rowHeight
            val rowY = canvas.top(rowTop + rowHeight)
            if (index % 2 == 1) canvas.fillRect(x, rowY, width, rowHeight, ReportTheme.MINT_SOFT)
            canvas.line(x, rowY, x + width, rowY, ReportTheme.LINE, 0.45f)
            val centreY = canvas.top(rowTop + rowHeight / 2f)
            canvas.textC((index + 1).toString(), x + widths[0] / 2f, centreY - 0.36f * 8f, canvas.theme.sans, 8f, ReportTheme.MUTED)
            val textX = x + widths[0] + 10f
            val name = rating.strandName?.takeIf { it.isNotBlank() } ?: rating.strandCode
            canvas.text(canvas.ellipsis(name, canvas.theme.sansBold, 9f, widths[1] - 14f), textX, centreY + 3f, canvas.theme.sansBold, 9f, ReportTheme.INK)
            if (rating.descriptor.isNotBlank()) {
                canvas.text(canvas.ellipsis(rating.descriptor, canvas.theme.sans, 7f, widths[1] - 14f), textX, centreY - 7f, canvas.theme.sans, 7f, ReportTheme.MUTED)
            }
            canvas.textC(rating.strandCode, x + widths[0] + widths[1] + widths[2] / 2f, centreY - 0.36f * 7.6f, canvas.theme.sansBold, 7.6f, ReportTheme.MUTED)
            ReportFurniture.cardChip(canvas, cbcLevel(rating.rating), x + widths[0] + widths[1] + widths[2] + widths[3] / 2f, centreY)
        }
        canvas.strokeRound(x, canvas.top(top + headerHeight + ratings.size * rowHeight), width, headerHeight + ratings.size * rowHeight, 3f, ReportTheme.LINE, 0.7f)
    }

    private fun summaryCards(canvas: ReportCanvas, entry: CbcStudentCard, top: Float) {
        val card = entry.card
        val x = 36f
        val gap = 14f
        val width = canvas.width - 72f
        val cardWidth = (width - gap) / 2f
        val height = 96f
        val y = canvas.top(top + height)

        canvas.box(x, y, cardWidth, height, 8f, ReportTheme.MINT, ReportTheme.TEAL, 0.8f)
        canvas.tracked("OVERALL ACHIEVEMENT", x + 16f, y + height - 16f, canvas.theme.sansBold, 5.8f, 1.3f, ReportTheme.TEAL_DARK)
        canvas.text(card.overallGrade, x + 16f, y + height - 52f, canvas.theme.sansBlack, 30f, ReportTheme.NAVY)
        val gradeWidth = canvas.textWidth(card.overallGrade, canvas.theme.sansBlack, 30f)
        canvas.text(ReportTheme.levelName(card.overallGrade), x + 16f + gradeWidth + 12f, y + height - 46f, canvas.theme.sansBold, 9f, ReportTheme.NAVY)
        val stars = ReportTheme.levelStars(card.overallGrade)
        if (stars > 0) canvas.stars(x + 19f, y + height - 68f, stars, 3.8f, 2.4f, ReportTheme.GOLD)
        canvas.text("Most recent rating across " + card.strandRatings.size + " learning areas", x + 16f, y + 14f, canvas.theme.sans, 7.2f, ReportTheme.MUTED)

        val x2 = x + cardWidth + gap
        canvas.box(x2, y, cardWidth, height, 8f, ReportTheme.GOLD_SOFT, ReportTheme.GOLD, 0.7f)
        canvas.tracked("ATTENDANCE", x2 + 16f, y + height - 16f, canvas.theme.sansBold, 5.8f, 1.3f, ReportTheme.AMBER)
        canvas.text(ReportTheme.pct(card.attendancePercentage), x2 + 16f, y + height - 52f, canvas.theme.sansBlack, 29f, ReportTheme.NAVY)
        canvas.text("Recorded attendance this term", x2 + 16f, y + 14f, canvas.theme.sans, 7.2f, ReportTheme.MUTED)
        val gaugeX = x2 + cardWidth - 46f
        val gaugeY = y + height / 2f + 6f
        canvas.arc(gaugeX, gaugeY, 20f, 90f, -270f, ReportTheme.mix(ReportTheme.LINE, ReportTheme.PAPER, 0.2f), 5.2f)
        canvas.arc(gaugeX, gaugeY, 20f, 90f, (90f - 360f * card.attendancePercentage / 100f).toFloat(), ReportTheme.TEAL, 5.2f)
        canvas.textC(Math.round(card.attendancePercentage).toString(), gaugeX, gaugeY - 2f, canvas.theme.sansBold, 8.4f, ReportTheme.NAVY)
    }

    /** Renders the paper-exam block + comments; returns the bottom offset of the block. */
    private fun bottomSection(
        canvas: ReportCanvas,
        entry: CbcStudentCard,
        papers: List<PaperExamResultPayload>,
        top: Float,
        paperLine: Float,
        commentsHeight: Float,
    ): Float {
        val x = 36f
        val width = canvas.width - 72f
        val twoColumn = papers.size > 6
        return if (!twoColumn) {
            val height = max(commentsHeight, 40f + papers.size * paperLine)
            val y = canvas.top(top + height)
            paperBox(canvas, papers, x, y, 250f, height, paperLine)
            commentsBox(canvas, entry, x + 250f + 14f, y, width - 250f - 14f, height)
            top + height
        } else {
            val paperHeight = 40f + ceil(papers.size / 2.0).toFloat() * (paperLine - 3f) + 8f
            val paperY = canvas.top(top + paperHeight)
            paperBoxTwoColumns(canvas, papers, x, paperY, width, paperHeight, paperLine - 3f)
            val commentsY = canvas.top(top + paperHeight + 10f + commentsHeight)
            commentsBox(canvas, entry, x, commentsY, width, commentsHeight)
            top + paperHeight + 10f + commentsHeight
        }
    }

    private fun paperBox(canvas: ReportCanvas, papers: List<PaperExamResultPayload>, x: Float, y: Float, width: Float, height: Float, paperLine: Float) {
        canvas.box(x, y, width, height, 8f, ReportTheme.PAPER, ReportTheme.LINE, 0.7f)
        canvas.tracked("PAPER EXAM RESULTS", x + 16f, y + height - 17f, canvas.theme.sansBold, 5.8f, 1.3f, ReportTheme.NAVY)
        examLabel(canvas, papers, x + 16f, y + height - 30f, width - 32f)
        if (papers.isEmpty()) {
            canvas.text("No paper-exam marks recorded.", x + 16f, y + height - 48f, canvas.theme.sans, 8f, ReportTheme.MUTED)
            return
        }
        var lineY = y + height - 46f
        papers.forEach { paper ->
            paperRow(canvas, paper, x + 16f, lineY, width - 32f)
            lineY -= paperLine
        }
    }

    private fun paperBoxTwoColumns(canvas: ReportCanvas, papers: List<PaperExamResultPayload>, x: Float, y: Float, width: Float, height: Float, paperLine: Float) {
        canvas.box(x, y, width, height, 8f, ReportTheme.PAPER, ReportTheme.LINE, 0.7f)
        canvas.tracked("PAPER EXAM RESULTS", x + 16f, y + height - 17f, canvas.theme.sansBold, 5.8f, 1.3f, ReportTheme.NAVY)
        examLabel(canvas, papers, x + 16f, y + height - 30f, width - 32f)
        val columnWidth = (width - 44f) / 2f
        val perColumn = ceil(papers.size / 2.0).toInt()
        var lineY = y + height - 46f
        for (index in 0 until perColumn) {
            val left = papers.getOrNull(index)
            val right = papers.getOrNull(index + perColumn)
            left?.let { paperRow(canvas, it, x + 16f, lineY, columnWidth) }
            right?.let { paperRow(canvas, it, x + 16f + columnWidth + 12f, lineY, columnWidth) }
            lineY -= paperLine
        }
    }

    private fun paperRow(canvas: ReportCanvas, paper: PaperExamResultPayload, x: Float, y: Float, width: Float) {
        val chipWidth = 26f
        canvas.text(canvas.ellipsis(paper.subject, canvas.theme.sans, 8f, width - chipWidth - 62f), x, y, canvas.theme.sans, 8f, ReportTheme.INK)
        canvas.textR(
            String.format(Locale.US, "%.0f / %.0f", paper.score, paper.maxScore),
            x + width - chipWidth - 8f,
            y,
            canvas.theme.sansBold,
            8f,
            ReportTheme.NAVY,
        )
        ReportFurniture.cardChipRight(canvas, paper.grade, x + width, y + 3f)
    }

    /** Small exam/term/date label above the paper rows; blank when the source has none. */
    private fun examLabel(canvas: ReportCanvas, papers: List<PaperExamResultPayload>, x: Float, y: Float, width: Float) {
        val first = papers.firstOrNull() ?: return
        val label = listOf(
            first.examTitle,
            listOf(first.term, first.year.takeIf { it > 0 }?.toString().orEmpty()).filter { it.isNotBlank() }.joinToString(" "),
            first.examDate,
        ).filter { it.isNotBlank() }.joinToString("  \u00B7  ")
        if (label.isNotBlank()) {
            canvas.text(canvas.ellipsis(label, canvas.theme.sans, 7f, width), x, y, canvas.theme.sans, 7f, ReportTheme.MUTED)
        }
    }

    private fun commentsBox(canvas: ReportCanvas, entry: CbcStudentCard, x: Float, y: Float, width: Float, height: Float) {
        canvas.box(x, y, width, height, 8f, ReportTheme.PAPER, ReportTheme.LINE, 0.7f)
        canvas.fillRect(x, y + 10f, 3.4f, height - 20f, ReportTheme.TEAL)
        canvas.tracked("TEACHER'S COMMENTS", x + 18f, y + height - 17f, canvas.theme.sansBold, 5.8f, 1.3f, ReportTheme.NAVY)
        val comments = entry.card.teacherComments
        val lines = if (comments.isBlank()) {
            listOf("No comments recorded for this term.")
        } else {
            canvas.wrap(comments, canvas.theme.sansItalic, 8.6f, width - 40f)
        }
        var lineY = y + height - 34f
        lines.take(3).forEach { line ->
            canvas.text(line, x + 18f, lineY, canvas.theme.sansItalic, 8.6f, if (comments.isBlank()) ReportTheme.MUTED else ReportTheme.INK)
            lineY -= 11.5f
        }
        ReportFurniture.signature(canvas, x + width - 160f, y + 34f, ReportTheme.NAVY)
        canvas.line(x + width - 170f, y + 28f, x + width - 16f, y + 28f, ReportTheme.LINE, 0.6f)
        canvas.trackedR("CLASS TEACHER", x + width - 16f, y + 14f, canvas.theme.sansBold, 5.4f, 1f, ReportTheme.MUTED)
    }

    private fun gradingKey(canvas: ReportCanvas, top: Float) {
        val y = canvas.top(top)
        val grades = listOf("EE", "ME", "AE", "BE", "DE")
        val labels = listOf("Exceeding", "Meeting", "Approaching", "Below", "Developing")
        var total = 0f
        labels.forEach { total += canvas.textWidth(it, canvas.theme.sans, 6.4f) + 26f }
        var x = (canvas.width - total) / 2f
        grades.forEachIndexed { index, grade ->
            ReportFurniture.cardChip(canvas, grade, x + 9f, y + 2.5f)
            canvas.text(labels[index], x + 22f, y, canvas.theme.sans, 6.4f, ReportTheme.MUTED)
            x += canvas.textWidth(labels[index], canvas.theme.sans, 6.4f) + 26f
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

    private fun prettyTerm(term: String): String {
        val trimmed = term.trim()
        if (trimmed.uppercase().startsWith("TERM_")) {
            return "Term " + trimmed.substringAfter("TERM_").trim()
        }
        return trimmed
    }

    /** CBC ratings are stored as words; the card shows the v1 two-letter level. */
    private fun cbcLevel(rating: String): String = when (rating.trim().uppercase()) {
        "EXCEEDING" -> "EE"
        "MEETING" -> "ME"
        "APPROACHING" -> "AE"
        "BELOW" -> "BE"
        "DEVELOPING" -> "DE"
        else -> rating.trim().take(2).uppercase()
    }
}
