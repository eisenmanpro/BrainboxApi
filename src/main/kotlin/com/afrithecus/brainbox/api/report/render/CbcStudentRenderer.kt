package com.afrithecus.brainbox.api.report.render

import com.afrithecus.brainbox.api.cbcratings.web.CbcReportCardPayload
import com.afrithecus.brainbox.api.cbcratings.web.CbcStrandRatingPayload
import com.afrithecus.brainbox.api.cbcratings.web.PaperExamResultPayload
import com.afrithecus.brainbox.api.report.CbcStudentCard
import com.afrithecus.brainbox.api.report.CbcStudentsSpec
import com.afrithecus.brainbox.api.report.ReportBranding
import com.afrithecus.brainbox.api.report.ReportProperties
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject
import org.springframework.stereotype.Component
import java.awt.Color
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
 *
 * DETAILED_CBC_STUDENT (plan A7, type 5, Q9 best case) renders the same card as page 1 of
 * each learner's document and appends one analytics page: engagement KPI tiles from
 * [CbcStudentCard.detail], a large attendance ring, the learning-area that needs support
 * with a level-based next step, the learner's level mix and the Exam Office sign-off. The
 * analytics page deliberately does not repeat the page-1 learning-area table (F7); detail
 * analytics that the server could not resolve render as an explicit unavailable state.
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
            // One page per card, plus the analytics page in the detailed variant. Page numbers
            // span the whole document, so learner k owns pages (2k+1, 2k+2) of 2 * cards.
            val pagesPerCard = if (spec.detailed) 2 else 1
            val documentPages = spec.cards.size * pagesPerCard
            spec.cards.forEachIndexed { index, entry ->
                val firstPage = index * pagesPerCard + 1
                ReportCanvas(document, theme, 595.28f, 841.89f).use { canvas ->
                    drawCard(canvas, spec.branding, logo, entry, firstPage, documentPages)
                }
                if (spec.detailed) {
                    ReportCanvas(document, theme, 595.28f, 841.89f).use { canvas ->
                        drawAnalyticsPage(canvas, spec.branding, logo, entry, firstPage + 1, documentPages)
                    }
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

    // ------------------------------------------------ DETAILED analytics page (A7)

    /**
     * Page 2 of DETAILED_CBC_STUDENT (A7/Q9 best case): a compact continuation header, the
     * four engagement KPI tiles, the attendance ring, the learning area that needs support,
     * the learner's level mix and the Exam Office sign-off. Geometry is fixed so the page
     * always fits one A4 sheet, and the page-1 learning-area table is never repeated (F7).
     */
    private fun drawAnalyticsPage(
        canvas: ReportCanvas,
        branding: ReportBranding,
        logo: PDImageXObject?,
        entry: CbcStudentCard,
        page: Int,
        pages: Int,
    ) {
        val termLabel = prettyTerm(entry.card.term)
        val yearLabel = entry.year?.toString().orEmpty()
        ReportFurniture.cardWatermark(canvas, branding)
        analyticsHeader(canvas, branding, logo, entry, termLabel, yearLabel)

        val kpiTop = CONTENT_TOP + 4f
        engagementTiles(canvas, entry, kpiTop)

        val attendanceTop = kpiTop + TILE_HEIGHT + SECTION_GAP
        ReportFurniture.eyebrow(canvas, "Attendance", MARGIN, attendanceTop)
        ReportFurniture.eyebrow(canvas, "Focus for next term", MARGIN + LEFT_PANEL_WIDTH + PANEL_GAP, attendanceTop)
        val panelTop = attendanceTop + 12f
        val rightPanelWidth = canvas.width - MARGIN * 2 - LEFT_PANEL_WIDTH - PANEL_GAP
        attendancePanel(canvas, entry.card, MARGIN, panelTop, LEFT_PANEL_WIDTH, PANEL_HEIGHT)
        focusPanel(canvas, entry.card, MARGIN + LEFT_PANEL_WIDTH + PANEL_GAP, panelTop, rightPanelWidth, PANEL_HEIGHT)

        val mixSectionTop = panelTop + PANEL_HEIGHT + SECTION_GAP
        ReportFurniture.eyebrow(canvas, "Learning-area level mix", MARGIN, mixSectionTop)
        val mixTop = mixSectionTop + 12f
        levelMixPanel(canvas, entry.card, MARGIN, mixTop)
        analyticsSignOff(canvas, entry, mixTop + MIX_HEIGHT + 60f)
        ReportFurniture.cardFooter(canvas, branding, termLabel + " " + yearLabel, page, pages)
    }

    /**
     * Compact continuation header in the v1 card language: double frame, crest and school
     * name, the serif analytics title with gold diamonds, and the learner/term subtitle.
     * Deliberately slimmer than page 1's full hero so the two pages read as one document
     * without printing the same header twice.
     */
    private fun analyticsHeader(
        canvas: ReportCanvas,
        branding: ReportBranding,
        logo: PDImageXObject?,
        entry: CbcStudentCard,
        termLabel: String,
        yearLabel: String,
    ) {
        val width = canvas.width
        val height = canvas.height
        canvas.strokeRect(11f, 11f, width - 22f, height - 22f, ReportTheme.NAVY, 0.9f)
        canvas.strokeRect(15f, 15f, width - 30f, height - 30f, ReportTheme.mix(ReportTheme.NAVY, ReportTheme.PAPER, 0.72f), 0.4f)
        canvas.fillRect(15f, height - 19f, width - 30f, 3.2f, ReportTheme.NAVY)

        val crestX = MARGIN + 10f
        val crestCy = height - 46f
        val crestR = 16.5f
        canvas.fillRound(crestX - crestR, crestCy - crestR, crestR * 2f, crestR * 2f, crestR, ReportTheme.MINT)
        canvas.ring(crestX, crestCy, crestR, ReportTheme.NAVY, 1.2f)
        canvas.ring(crestX, crestCy, crestR - 3.6f, ReportTheme.GOLD, 0.5f)
        val image = logo ?: ReportImages.brainboxMark(canvas.document)
        if (image != null) {
            canvas.fillRound(crestX - crestR + 4f, crestCy - crestR + 4f, (crestR - 4f) * 2f, (crestR - 4f) * 2f, crestR - 4f, ReportTheme.PAPER)
            val max = crestR * 2f - 9f
            val scale = minOf(max / image.width, max / image.height)
            val imageWidth = image.width * scale
            val imageHeight = image.height * scale
            canvas.image(image, crestX - imageWidth / 2f, crestCy - imageHeight / 2f, imageWidth, imageHeight)
        } else {
            canvas.textC(ReportFurniture.initials(branding.schoolName), crestX, crestCy - 4.2f, canvas.theme.serifBold, 10f, ReportTheme.NAVY)
        }

        val textX = crestX + crestR + 13f
        val nameRoom = width - MARGIN - 130f - textX
        var nameSize = 13.8f
        while (nameSize > 9f && canvas.textWidth(branding.schoolName, canvas.theme.serifBold, nameSize) > nameRoom) nameSize -= 0.5f
        canvas.text(
            canvas.ellipsis(branding.schoolName, canvas.theme.serifBold, nameSize, nameRoom),
            textX,
            height - 42f,
            canvas.theme.serifBold,
            nameSize,
            ReportTheme.NAVY,
        )
        val motto = branding.motto?.takeIf { it.isNotBlank() } ?: "Continued report"
        canvas.tracked(
            canvas.ellipsis(motto.uppercase(), canvas.theme.sansBold, 5.5f, nameRoom),
            textX,
            height - 53f,
            canvas.theme.sansBold,
            5.5f,
            1.3f,
            ReportTheme.TEAL,
        )

        val rightEdge = width - MARGIN - 10f
        canvas.trackedR("ACADEMIC YEAR", rightEdge, height - 42f, canvas.theme.sansBold, 5.2f, 1.2f, ReportTheme.MUTED)
        canvas.textR(yearLabel, rightEdge, height - 54f, canvas.theme.sansBold, 9.6f, ReportTheme.NAVY)

        val title = "CBC Analytics Report"
        val titleY = height - 82f
        val titleWidth = canvas.textWidth(title, canvas.theme.serifBold, 17.5f)
        val centreX = width / 2f
        canvas.line(MARGIN + 24f, titleY + 2f, centreX - titleWidth / 2f - 18f, titleY + 2f, ReportTheme.LINE, 0.7f)
        canvas.line(centreX + titleWidth / 2f + 18f, titleY + 2f, width - MARGIN - 24f, titleY + 2f, ReportTheme.LINE, 0.7f)
        canvas.diamond(MARGIN + 24f, titleY + 2f, 2.2f, ReportTheme.GOLD)
        canvas.diamond(width - MARGIN - 24f, titleY + 2f, 2.2f, ReportTheme.GOLD)
        canvas.textC(title, centreX, titleY, canvas.theme.serifBold, 17.5f, ReportTheme.NAVY)

        val learner = listOf(
            entry.studentName,
            entry.gradeLevel ?: entry.card.gradeLevel.orEmpty(),
            (termLabel + " " + yearLabel).trim(),
        ).filter { it.isNotBlank() }.joinToString("   \u00B7   ")
        canvas.trackedC(
            canvas.ellipsis(learner.uppercase(), canvas.theme.sansBold, 6.4f, width - MARGIN * 2 - 44f),
            centreX,
            height - 96f,
            canvas.theme.sansBold,
            6.4f,
            1.5f,
            ReportTheme.MUTED,
        )
    }

    /** Four engagement KPIs from [CbcStudentCard.detail]; null analytics stay explicit. */
    private fun engagementTiles(canvas: ReportCanvas, entry: CbcStudentCard, top: Float) {
        val detail = entry.detail
        val x = MARGIN
        val width = canvas.width - MARGIN * 2
        val tileWidth = (width - TILE_GAP * 3f) / 4f
        val unavailable = listOf("Not available for", "this viewer")
        val notRecorded = listOf("Not recorded", "this term")
        val hasDetail = detail != null
        val hasAverage = detail?.classAverageScore != null
        val hasPercentile = (detail?.classPercentile ?: 0) > 0
        val streak = detail?.learningStreakDays?.toString() ?: EM_DASH
        val xp = detail?.let { String.format(Locale.US, "%,d", it.totalXp) } ?: EM_DASH
        val average = if (hasAverage) ReportTheme.pct(detail!!.classAverageScore!!) else EM_DASH
        val percentile = if (hasPercentile) ordinal(detail!!.classPercentile!!) else EM_DASH
        engagementTile(
            canvas, x, top, tileWidth, "LEARNING STREAK", streak,
            if (hasDetail) listOf("Days in a row") else unavailable, ReportTheme.TEAL, ICON_STREAK,
        )
        engagementTile(
            canvas, x + tileWidth + TILE_GAP, top, tileWidth, "TOTAL XP", xp,
            if (hasDetail) listOf("Points earned") else unavailable, ReportTheme.GOLD, ICON_XP,
        )
        engagementTile(
            canvas, x + (tileWidth + TILE_GAP) * 2f, top, tileWidth, "CLASS AVERAGE", average,
            if (hasAverage) listOf("Class mean this term") else if (hasDetail) notRecorded else unavailable,
            ReportTheme.NAVY_SOFT, ICON_AVERAGE,
        )
        engagementTile(
            canvas, x + (tileWidth + TILE_GAP) * 3f, top, tileWidth, "CLASS PERCENTILE", percentile,
            if (hasPercentile) listOf("Relative standing") else if (hasDetail) notRecorded else unavailable,
            ReportTheme.TEAL, ICON_PERCENTILE,
        )
    }

    private fun engagementTile(
        canvas: ReportCanvas,
        x: Float,
        top: Float,
        width: Float,
        label: String,
        value: String,
        caption: List<String>,
        accent: Color,
        icon: Int,
    ) {
        val y = canvas.top(top + TILE_HEIGHT)
        canvas.box(x, y, width, TILE_HEIGHT, 7f, ReportTheme.PAPER, ReportTheme.LINE, 0.8f)
        canvas.fillInk(ReportTheme.mix(accent, ReportTheme.PAPER, 0.82f))
        canvas.circle(x + 22f, y + TILE_HEIGHT / 2f, 13f)
        canvas.stream.fill()
        kpiIcon(canvas, icon, x + 22f, y + TILE_HEIGHT / 2f, accent)
        val textX = x + 42f
        val textWidth = width - 54f
        canvas.tracked(label, textX, y + TILE_HEIGHT - 15f, canvas.theme.sansBold, 5.2f, 0.9f, ReportTheme.MUTED)
        var size = 19f
        while (size > 11f && canvas.textWidth(value, canvas.theme.sansBlack, size) > textWidth) size -= 0.5f
        canvas.text(value, textX, y + TILE_HEIGHT - 38f, canvas.theme.sansBlack, size, ReportTheme.NAVY)
        caption.take(2).forEachIndexed { index, line ->
            canvas.text(
                canvas.ellipsis(line, canvas.theme.sans, 6.2f, textWidth),
                textX,
                y + TILE_HEIGHT - 53f - index * 9f,
                canvas.theme.sans,
                6.2f,
                ReportTheme.MUTED,
            )
        }
    }

    /** Simple v1-style line glyphs for the KPI discs (no icon font, print-safe). */
    private fun kpiIcon(canvas: ReportCanvas, kind: Int, cx: Float, cy: Float, ink: Color) {
        when (kind) {
            ICON_STREAK -> {
                canvas.fillInk(ink)
                canvas.stream.moveTo(cx - 1.4f, cy + 7f)
                canvas.stream.lineTo(cx + 5.4f, cy + 7f)
                canvas.stream.lineTo(cx + 1.6f, cy + 0.4f)
                canvas.stream.lineTo(cx + 5.6f, cy + 0.4f)
                canvas.stream.lineTo(cx - 4.8f, cy - 7f)
                canvas.stream.lineTo(cx - 1.6f, cy - 1.4f)
                canvas.stream.lineTo(cx - 5.6f, cy - 1.4f)
                canvas.stream.closePath()
                canvas.stream.fill()
            }
            ICON_XP -> canvas.star(cx, cy, 6.6f, ink)
            ICON_AVERAGE -> {
                canvas.fillRect(cx - 6.2f, cy - 6f, 3.2f, 6f, ink)
                canvas.fillRect(cx - 1.6f, cy - 6f, 3.2f, 9.6f, ink)
                canvas.fillRect(cx + 3f, cy - 6f, 3.2f, 13.2f, ink)
            }
            else -> {
                canvas.ring(cx, cy, 6.6f, ink, 1.7f)
                canvas.ring(cx, cy, 3.6f, ink, 1.2f)
                canvas.fillInk(ink)
                canvas.circle(cx, cy, 1.1f)
                canvas.stream.fill()
            }
        }
    }

    /** Large attendance donut with the measured percentage and its register source. */
    private fun attendancePanel(canvas: ReportCanvas, card: CbcReportCardPayload, x: Float, top: Float, width: Float, height: Float) {
        val y = canvas.top(top + height)
        canvas.box(x, y, width, height, 8f, ReportTheme.PAPER, ReportTheme.LINE, 0.8f)
        val centreX = x + width / 2f
        val centreY = canvas.top(top + 88f)
        val radius = 52f
        val measured = card.attendancePercentage.coerceIn(0.0, 100.0)
        canvas.fillInk(ReportTheme.MINT_SOFT)
        canvas.circle(centreX, centreY, radius - 8f)
        canvas.stream.fill()
        canvas.arc(centreX, centreY, radius, 90f, -270f, ReportTheme.mix(ReportTheme.LINE, ReportTheme.PAPER, 0.15f), 13f)
        if (measured > 0.0) {
            canvas.arc(centreX, centreY, radius, 90f, (90f - 360f * measured / 100f).toFloat(), ReportTheme.TEAL, 13f)
        }
        canvas.textC(ReportTheme.pct(card.attendancePercentage), centreX, centreY - 6f, canvas.theme.sansBlack, 19f, ReportTheme.NAVY)
        canvas.textC("Recorded attendance this term", centreX, canvas.top(top + 160f), canvas.theme.sans, 7f, ReportTheme.MUTED)
        canvas.textC("Taken from the school register", centreX, canvas.top(top + 172f), canvas.theme.sans, 6.2f, ReportTheme.MUTED)
    }

    /** Lowest-rated learning area with a fixed, level-based next step (never a new measure). */
    private fun focusPanel(canvas: ReportCanvas, card: CbcReportCardPayload, x: Float, top: Float, width: Float, height: Float) {
        val y = canvas.top(top + height)
        val focus = lowestRatedArea(card.strandRatings)
        val level = focus?.let { cbcLevel(it.rating) } ?: ""
        val accent = if (level.isBlank()) ReportTheme.MUTED else ReportTheme.gradeInk(level)
        canvas.box(x, y, width, height, 8f, ReportTheme.PAPER, ReportTheme.LINE, 0.8f)
        canvas.fillRect(x, y + 12f, 3.4f, height - 24f, accent)
        canvas.tracked("LOWEST-RATED LEARNING AREA", x + 20f, canvas.top(top + 20f), canvas.theme.sansBold, 5.2f, 1.2f, ReportTheme.MUTED)
        if (focus == null) {
            canvas.text("No learning-area ratings recorded this term.", x + 20f, canvas.top(top + 44f), canvas.theme.sans, 8.2f, ReportTheme.MUTED)
            return
        }
        val name = focus.strandName?.takeIf { it.isNotBlank() } ?: focus.strandCode
        canvas.text(canvas.ellipsis(name, canvas.theme.serifBold, 13.5f, width - 40f), x + 20f, canvas.top(top + 42f), canvas.theme.serifBold, 13.5f, ReportTheme.NAVY)

        val chipWidth = canvas.textWidth(level, canvas.theme.sansBold, 7.2f) + 16f
        ReportFurniture.cardChip(canvas, level, x + 20f + chipWidth / 2f, canvas.top(top + 60f))
        val levelName = ReportTheme.levelName(level)
        if (levelName.isNotBlank()) {
            canvas.text(levelName, x + 20f + chipWidth + 9f, canvas.top(top + 63f), canvas.theme.sansBold, 8f, accent)
        }
        if (focus.descriptor.isNotBlank()) {
            canvas.wrap(focus.descriptor, canvas.theme.sansItalic, 7.2f, width - 40f).take(2).forEachIndexed { index, line ->
                canvas.text(line, x + 20f, canvas.top(top + 78f + index * 10f), canvas.theme.sansItalic, 7.2f, ReportTheme.MUTED)
            }
        }

        val actionTop = top + 102f
        val actionHeight = 70f
        canvas.box(x + 20f, canvas.top(actionTop + actionHeight), width - 40f, actionHeight, 6f, ReportTheme.MINT_SOFT, ReportTheme.LINE, 0.7f)
        canvas.tracked("RECOMMENDED ACTION", x + 32f, canvas.top(actionTop + 15f), canvas.theme.sansBold, 5.2f, 1.2f, ReportTheme.TEAL_DARK)
        canvas.wrap(recommendedAction(level), canvas.theme.sans, 8f, width - 64f).take(3).forEachIndexed { index, line ->
            canvas.text(line, x + 32f, canvas.top(actionTop + 31f + index * 11f), canvas.theme.sans, 8f, ReportTheme.INK)
        }
    }

    /**
     * Level order EE > ME > AE > BE > DE (Q9/F7): the lowest-rated learning area, ties
     * resolved by payload order so a re-render is deterministic. An unknown rating ranks
     * lowest because it carries no evidence of achievement.
     */
    private fun lowestRatedArea(ratings: List<CbcStrandRatingPayload>): CbcStrandRatingPayload? =
        ratings.minByOrNull { levelRank(cbcLevel(it.rating)) }

    private fun levelRank(level: String): Int = when (level) {
        "EE" -> 5
        "ME" -> 4
        "AE" -> 3
        "BE" -> 2
        "DE" -> 1
        else -> 0
    }

    /**
     * Fixed next step per assessment level (Q9). These are guidance sentences, not data:
     * the page never adds an attendance session, rank or score, only the standard action
     * for the level the assessment register already recorded.
     */
    private fun recommendedAction(level: String): String = when (level) {
        "EE" -> "Keep the current enrichment tasks and offer leadership opportunities in this learning area."
        "ME" -> "Hold the current support and add a stretch task so the learner can reach Exceeding Expectations."
        "AE" -> "Plan short, guided practice in this learning area and review progress at the next assessment."
        "BE" -> "Provide targeted small-group support with modelled examples, then re-assess within two weeks."
        "DE" -> "Agree a focused learning plan with the learner and guardian, then re-assess after two weeks."
        else -> "Plan targeted support for this learning area and review it at the next assessment."
    }

    /** Stacked level-mix bar with per-level counts and the ME-or-above summary (Q9). */
    private fun levelMixPanel(canvas: ReportCanvas, card: CbcReportCardPayload, x: Float, top: Float) {
        val width = canvas.width - MARGIN * 2
        val y = canvas.top(top + MIX_HEIGHT)
        canvas.box(x, y, width, MIX_HEIGHT, 8f, ReportTheme.PAPER, ReportTheme.LINE, 0.8f)
        val order = listOf("EE", "ME", "AE", "BE", "DE")
        val ratings = card.strandRatings
        val counts = order.associateWith { level -> ratings.count { cbcLevel(it.rating) == level } }
        val total = ratings.size
        val atOrAbove = (counts["EE"] ?: 0) + (counts["ME"] ?: 0)
        val headline = if (total == 0) {
            "No learning-area ratings recorded this term."
        } else {
            atOrAbove.toString() + " of " + total + " learning areas at ME or above"
        }
        canvas.text(headline, x + 18f, canvas.top(top + 26f), canvas.theme.sansBold, 10.5f, ReportTheme.NAVY)
        canvas.trackedR(
            total.toString() + " LEARNING AREAS ASSESSED",
            x + width - 18f,
            canvas.top(top + 26f),
            canvas.theme.sansBold,
            5.2f,
            1.2f,
            ReportTheme.MUTED,
        )

        val barX = x + 18f
        val barWidth = width - 36f
        val barHeight = 30f
        val barY = canvas.top(top + 54f + barHeight)
        canvas.fillRound(barX, barY, barWidth, barHeight, 4f, ReportTheme.mix(ReportTheme.LINE, ReportTheme.PAPER, 0.25f))
        if (total > 0) {
            var cursor = barX
            order.forEach { level ->
                val count = counts[level] ?: 0
                if (count > 0) {
                    val segment = barWidth * count / total
                    canvas.fillRect(cursor, barY, segment, barHeight, levelMixFill(level))
                    if (segment >= 16f) {
                        canvas.textC(
                            count.toString(),
                            cursor + segment / 2f,
                            barY + barHeight / 2f - 2.8f,
                            canvas.theme.sansBold,
                            8.2f,
                            ReportTheme.PAPER,
                        )
                    }
                    cursor += segment
                }
            }
            canvas.strokeRound(barX, barY, barWidth, barHeight, 4f, ReportTheme.mix(ReportTheme.LINE, ReportTheme.PAPER, 0.1f), 0.7f)

            val legendBaseline = canvas.top(top + 112f)
            var legendX = x + 18f
            order.forEach { level ->
                val count = counts[level] ?: 0
                canvas.fillRound(legendX, legendBaseline - 8f, 18f, 12f, 2.4f, ReportTheme.gradeFill(level))
                canvas.textC(level, legendX + 9f, legendBaseline - 2.6f, canvas.theme.sansBold, 6.2f, ReportTheme.gradeInk(level))
                val label = count.toString() + " " + ReportTheme.shortLevel(level)
                canvas.text(label, legendX + 24f, legendBaseline, canvas.theme.sans, 6.8f, ReportTheme.MUTED)
                legendX += 24f + canvas.textWidth(label, canvas.theme.sans, 6.8f) + 18f
            }
            val note = "Counts are taken from the " + total + " learning areas on page 1; this page adds no new rating."
            canvas.wrap(note, canvas.theme.sans, 6.4f, width - 36f).take(2).forEachIndexed { index, line ->
                canvas.text(line, x + 18f, canvas.top(top + 142f + index * 10f), canvas.theme.sans, 6.4f, ReportTheme.MUTED)
            }
        }
    }

    private fun levelMixFill(level: String): Color = when (level) {
        "EE" -> ReportTheme.TEAL
        "ME" -> ReportTheme.BLUE_INK
        "AE" -> ReportTheme.AMBER
        "BE" -> ReportTheme.RED
        "DE" -> ReportTheme.mix(ReportTheme.RED, ReportTheme.NAVY, 0.45f)
        else -> ReportTheme.MUTED
    }

    /** Exam Office stamp + official-record line; no personal signatory (D5/Q1). */
    private fun analyticsSignOff(canvas: ReportCanvas, entry: CbcStudentCard, top: Float) {
        val termLabel = prettyTerm(entry.card.term)
        val yearLabel = entry.year?.toString().orEmpty()
        ReportFurniture.divider(canvas, canvas.top(top), ReportTheme.LINE2)
        ReportFurniture.horizontalStamp(canvas, MARGIN, canvas.top(top + 40f))
        val textX = MARGIN + 118f + 20f
        val textWidth = canvas.width - MARGIN - textX
        canvas.tracked("OFFICIAL SCHOOL RECORD", textX, canvas.top(top + 24f), canvas.theme.sansBold, 6.6f, 1.4f, ReportTheme.NAVY)
        val note = "This analytics page is part of the CBC report card and the official school record for " +
            (termLabel + " " + yearLabel).trim() + ". It is validated by the Exam Office stamp."
        canvas.wrap(note, canvas.theme.sans, 6.9f, textWidth).take(2).forEachIndexed { index, line ->
            canvas.text(line, textX, canvas.top(top + 37f + index * 10.5f), canvas.theme.sans, 6.9f, ReportTheme.MUTED)
        }
    }

    /** 1 -> 1st, 2 -> 2nd, 11 -> 11th (percentile KPI). */
    private fun ordinal(value: Int): String {
        val suffix = when {
            value % 100 in 11..13 -> "th"
            value % 10 == 1 -> "st"
            value % 10 == 2 -> "nd"
            value % 10 == 3 -> "rd"
            else -> "th"
        }
        return value.toString() + suffix
    }

    private companion object {
        private const val MARGIN = 36f
        private const val CONTENT_TOP = 108f
        private const val TILE_HEIGHT = 78f
        private const val TILE_GAP = 12f
        private const val SECTION_GAP = 28f
        private const val LEFT_PANEL_WIDTH = 232f
        private const val PANEL_GAP = 14f
        private const val PANEL_HEIGHT = 188f
        private const val MIX_HEIGHT = 174f
        private const val ICON_STREAK = 0
        private const val ICON_XP = 1
        private const val ICON_AVERAGE = 2
        private const val ICON_PERCENTILE = 3
        private const val EM_DASH = "\u2014"
    }
}
