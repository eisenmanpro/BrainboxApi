package com.afrithecus.brainbox.api.report.render

import com.afrithecus.brainbox.api.cbcratings.web.CbcClassReportPayload
import com.afrithecus.brainbox.api.cbcratings.web.CbcStrandMasteryPayload
import com.afrithecus.brainbox.api.report.CbcClassSpec
import com.afrithecus.brainbox.api.report.ReportBranding
import com.afrithecus.brainbox.api.report.ReportProperties
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.font.PDFont
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject
import org.springframework.stereotype.Component
import java.awt.Color
import java.io.ByteArrayOutputStream
import kotlin.math.min

/**
 * CBC_CLASS report (plan A5, type 3, v1 page 03) and DETAILED_CBC_CLASS (plan A8, type 6).
 *
 * Page 1 is the v1 class report: hero header, four KPI tiles (overall average, strands
 * assessed, on track, needs support - with the real roster count from D2), strand mastery
 * bars with On Track / Needs Support chips, subject and teacher impact bars, and the "focus
 * for next term" callout built from the weakest strands.
 *
 * DETAILED_CBC_CLASS (A8/Q10) keeps that page and **only** appends a second page when at
 * least one weak strand carries a recommendation. The second page is a compact continuation
 * sheet in the same v1 table language: a "Weak strand recommendations" table (strand,
 * average, learners needing support, recommendation), a key-figures strip derived from the
 * same payload, the Exam Office stamp and the official-record line. It never repeats the
 * class strand list, so on-track learning areas cannot leak onto it (A8/F7).
 *
 * Teacher performance (A6) is a different report (TeacherPerformanceRenderer) and never
 * renders through this class-level view.
 */
@Component
class CbcClassRenderer(
    private val properties: ReportProperties = ReportProperties(),
) {

    fun render(spec: CbcClassSpec): ByteArray {
        return PDDocument().use { document ->
            document.documentInformation.title = spec.title
            val theme = ReportTheme(document)
            val logo = ReportImages.brandingLogo(document, spec.branding.logoUrl, properties.embedLogo)
            val report = spec.classReport
            val recommendations = recommendationsFor(spec)
            val pages = if (recommendations.isEmpty()) 1 else 2
            val yearLabel = spec.year?.toString().orEmpty()
            ReportCanvas(document, theme, LANDSCAPE_WIDTH, LANDSCAPE_HEIGHT).use { canvas ->
                draw(canvas, spec.branding, logo, report, yearLabel, 1, pages)
            }
            if (recommendations.isNotEmpty()) {
                ReportCanvas(document, theme, LANDSCAPE_WIDTH, LANDSCAPE_HEIGHT).use { canvas ->
                    drawRecommendations(canvas, spec.branding, logo, report, recommendations, pages)
                }
            }
            val out = ByteArrayOutputStream()
            document.save(out)
            out.toByteArray()
        }
    }

    /**
     * A8: the recommendations page exists only for DETAILED_CBC_CLASS and only when a weak
     * strand actually has a recommendation - a weak strand without one is a data gap, not a
     * reason to print an empty page.
     */
    private fun recommendationsFor(spec: CbcClassSpec): List<CbcStrandMasteryPayload> {
        if (!spec.detailed) return emptyList()
        return spec.classReport.strandMastery.filter { it.isWeakStrand && !it.recommendation.isNullOrBlank() }
    }

    // ------------------------------------------------------------------ page 1 (A5)

    private fun draw(
        canvas: ReportCanvas,
        branding: ReportBranding,
        logo: PDImageXObject?,
        report: CbcClassReportPayload,
        specYear: String,
        page: Int,
        pages: Int,
    ) {
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
        ReportFurniture.cardFooter(canvas, branding, termLabel, page, pages)
    }

    // ------------------------------------------------- page 2 (A8 detailed recommendations)

    /**
     * A4 landscape continuation sheet. Reading order: compact identity header, the
     * recommendations table (the only place a weak strand is named), the derived key
     * figures, an optional plain-language review note, then the Exam Office sign-off.
     * Secondary blocks appear only when the table leaves room, so a many-strand class still
     * fits one sheet; nothing is ever clipped.
     */
    private fun drawRecommendations(
        canvas: ReportCanvas,
        branding: ReportBranding,
        logo: PDImageXObject?,
        report: CbcClassReportPayload,
        recommendations: List<CbcStrandMasteryPayload>,
        pages: Int,
    ) {
        val termLabel = prettyTerm(report.term)
        ReportFurniture.cardWatermark(canvas, branding)
        val contentTop = continuationHeader(canvas, branding, logo, report.className, termLabel)
        val contentWidth = canvas.width - 72f

        sectionHeading(canvas, "WEAK STRAND RECOMMENDATIONS", 36f, contentTop + 2f, contentWidth)
        summaryLine(canvas, recommendations.size, report.strandMastery.size, 36f, contentTop + 20f)

        val tableTop = contentTop + 34f
        val tableBottom = recommendationsTable(canvas, report, recommendations, tableTop, BOTTOM_CONTENT_LIMIT)

        // Totals-style figures under the table (the v1 "so what does this mean" idiom). A short
        // table gets the tall card treatment so a one- or two-finding page is not half empty.
        val tallFigures = recommendations.size <= 3
        val figuresHeight = if (tallFigures) TALL_FIGURES_HEIGHT else SHORT_FIGURES_HEIGHT
        val figuresTop = tableBottom + 18f
        val figuresShown = figuresTop + figuresHeight <= BOTTOM_CONTENT_LIMIT
        if (figuresShown) {
            keyFigures(canvas, report, recommendations, figuresTop, contentWidth, figuresHeight, tallFigures)
        }

        // Only the sparsest sheets have room for the plain-language review note; denser sheets
        // keep the table + figures and let the footnote carry the explanation.
        val followTop = if (figuresShown) figuresTop + figuresHeight + 16f else tableBottom + 18f
        if (followTop + FOLLOW_UP_HEIGHT <= BOTTOM_CONTENT_LIMIT) {
            followUp(canvas, recommendations.size, followTop, contentWidth)
        }

        signOff(canvas, contentWidth)
        ReportFurniture.cardFooter(canvas, branding, termLabel, pages, pages)
    }

    /** Compact continuation header: double frame, crest + school left, serif title, class + term right. */
    private fun continuationHeader(
        canvas: ReportCanvas,
        branding: ReportBranding,
        logo: PDImageXObject?,
        className: String,
        termLabel: String,
    ): Float {
        val width = canvas.width
        val height = canvas.height
        canvas.strokeRect(11f, 11f, width - 22f, height - 22f, ReportTheme.NAVY, 0.9f)
        canvas.strokeRect(15f, 15f, width - 30f, height - 30f, ReportTheme.mix(ReportTheme.NAVY, ReportTheme.PAPER, 0.72f), 0.4f)
        canvas.fillRect(15f, height - 19f, width - 30f, 3.2f, ReportTheme.NAVY)

        val crestX = 48f
        val crestR = 17f
        val crestCy = canvas.top(44f)
        canvas.fillRound(crestX - crestR, crestCy - crestR, crestR * 2, crestR * 2, crestR, ReportTheme.MINT)
        canvas.ring(crestX, crestCy, crestR, ReportTheme.NAVY, 1.3f)
        canvas.ring(crestX, crestCy, crestR - 3.6f, ReportTheme.GOLD, 0.45f)
        val image = logo ?: ReportImages.brainboxMark(canvas.document)
        if (image != null) {
            canvas.fillRound(crestX - crestR + 4f, crestCy - crestR + 4f, (crestR - 4f) * 2, (crestR - 4f) * 2, crestR - 4f, ReportTheme.PAPER)
            val max = crestR * 2 - 11f
            val scale = minOf(max / image.width, max / image.height)
            val imageWidth = image.width * scale
            val imageHeight = image.height * scale
            canvas.image(image, crestX - imageWidth / 2f, crestCy - imageHeight / 2f, imageWidth, imageHeight)
        } else {
            canvas.textC(ReportFurniture.initials(branding.schoolName), crestX, crestCy - 4.2f, canvas.theme.serifBold, 11f, ReportTheme.NAVY)
        }

        val title = "Detailed Class CBC Report"
        val titleSize = 19f
        val titleWidth = canvas.textWidth(title, canvas.theme.serifBold, titleSize)
        val centreX = width / 2f
        canvas.textC(title, centreX, canvas.top(40f), canvas.theme.serifBold, titleSize, ReportTheme.NAVY)
        canvas.trackedC("CLASS CBC REPORT  ·  RECOMMENDATIONS", centreX, canvas.top(55f), canvas.theme.sansBold, 6f, 1.3f, ReportTheme.MUTED)

        // school identity, kept clear of the centred title
        val textX = crestX + crestR + 13f
        val nameMax = centreX - titleWidth / 2f - 24f - textX
        var nameSize = 15f
        while (nameSize > 10f && canvas.textWidth(branding.schoolName, canvas.theme.serifBold, nameSize) > nameMax) nameSize -= 0.5f
        canvas.text(
            canvas.ellipsis(branding.schoolName, canvas.theme.serifBold, nameSize, nameMax),
            textX,
            canvas.top(37f),
            canvas.theme.serifBold,
            nameSize,
            ReportTheme.NAVY,
        )
        val motto = branding.motto?.takeIf { it.isNotBlank() }
        if (motto != null) {
            canvas.tracked(
                canvas.ellipsis(motto.uppercase(), canvas.theme.sansBold, 6.2f, nameMax),
                textX,
                canvas.top(51f),
                canvas.theme.sansBold,
                6.2f,
                1.35f,
                ReportTheme.TEAL,
            )
        } else {
            val contact = listOfNotNull(
                branding.address?.takeIf { it.isNotBlank() },
                branding.phone?.takeIf { it.isNotBlank() },
                branding.email?.takeIf { it.isNotBlank() },
            ).joinToString("   |   ")
            canvas.text(
                canvas.ellipsis(contact, canvas.theme.sans, 6.4f, nameMax),
                textX,
                canvas.top(51f),
                canvas.theme.sans,
                6.4f,
                ReportTheme.MUTED,
            )
        }

        // class + term, right, kept clear of the centred title
        val rightEdge = width - 36f
        val rightMax = centreX + titleWidth / 2f + 24f
        canvas.trackedR("CLASS  ·  TERM", rightEdge, canvas.top(34f), canvas.theme.sansBold, 5.6f, 1.2f, ReportTheme.MUTED)
        val meta = listOf(className, termLabel).filter { it.isNotBlank() }.joinToString("  ·  ")
        canvas.textR(
            canvas.ellipsis(meta, canvas.theme.sansBold, 9.6f, rightEdge - rightMax),
            rightEdge,
            canvas.top(48f),
            canvas.theme.sansBold,
            9.6f,
            ReportTheme.NAVY,
        )

        val ruleY = canvas.top(66f)
        canvas.line(36f, ruleY, width - 36f, ruleY, ReportTheme.LINE, 0.7f)
        canvas.diamond(36f, ruleY, 2f, ReportTheme.GOLD)
        canvas.diamond(width - 36f, ruleY, 2f, ReportTheme.GOLD)
        return 78f
    }

    /** Section label with the trailing hairline used across the v1 card pages. */
    private fun sectionHeading(canvas: ReportCanvas, label: String, x: Float, top: Float, width: Float) {
        val baseline = canvas.top(top)
        canvas.fillRect(x, baseline - 1.5f, 6f, 6f, ReportTheme.TEAL)
        canvas.tracked(label, x + 12f, baseline, canvas.theme.sansBold, 6.8f, 1.4f, ReportTheme.NAVY)
        val labelWidth = canvas.trackedWidth(label, canvas.theme.sansBold, 6.8f, 1.4f)
        canvas.line(x + 12f + labelWidth + 12f, baseline + 2f, x + width, baseline + 2f, ReportTheme.LINE, 0.6f)
    }

    private fun summaryLine(canvas: ReportCanvas, weakCount: Int, totalStrands: Int, x: Float, top: Float) {
        val count = if (totalStrands > 0) "$weakCount of $totalStrands learning areas" else "$weakCount learning areas"
        val rest = " below target - reviewed with subject teachers"
        val baseline = canvas.top(top)
        canvas.text(count, x, baseline, canvas.theme.sansBold, 8.8f, ReportTheme.AMBER)
        canvas.text(rest, x + canvas.textWidth(count, canvas.theme.sansBold, 8.8f), baseline, canvas.theme.sans, 8.8f, ReportTheme.MUTED)
    }

    /**
     * The recommendation table. Row height adapts to how many weak strands carry a
     * recommendation so two findings do not become two thin lines on a landscape page; when
     * a class has too many findings for one page the list truncates with an explicit note
     * instead of overflowing the sign-off block (A8/Q10).
     */
    private fun recommendationsTable(
        canvas: ReportCanvas,
        report: CbcClassReportPayload,
        recommendations: List<CbcStrandMasteryPayload>,
        top: Float,
        bottomLimit: Float,
    ): Float {
        val x = 36f
        val width = canvas.width - 72f
        val colStrand = 226f
        val colAverage = 100f
        val colLearners = 154f
        val colRecommendation = width - colStrand - colAverage - colLearners

        val headerFont = canvas.theme.sansBold
        val headerSize = 6.2f
        val headerSpacing = 1.1f
        val learnersHeader = "LEARNERS NEEDING SUPPORT"
        val learnersHeaderWidth = canvas.trackedWidth(learnersHeader, headerFont, headerSize, headerSpacing)
        val twoLineLearnerHeader = learnersHeaderWidth > colLearners - 28f
        val headerHeight = if (twoLineLearnerHeader) 32f else 26f

        val available = (bottomLimit - top - headerHeight).coerceAtLeast(0f)
        val idealRowHeight = when {
            recommendations.size <= 2 -> 96f
            recommendations.size <= 4 -> 72f
            recommendations.size <= 6 -> 56f
            else -> 48f
        }
        var shown = recommendations.size
        var rowHeight = min(idealRowHeight, available / shown)
        if (rowHeight < MIN_TABLE_ROW_HEIGHT) {
            // reserve one line for the "showing N of M" note before truncating
            val forRows = (available - 15f).coerceAtLeast(MIN_TABLE_ROW_HEIGHT)
            shown = (forRows / MIN_TABLE_ROW_HEIGHT).toInt().coerceIn(1, recommendations.size)
            rowHeight = min(idealRowHeight, forRows / shown)
        }
        val truncated = shown < recommendations.size

        canvas.fillRound(x, canvas.top(top + headerHeight), width, headerHeight, 3f, ReportTheme.NAVY)
        val headerBaseline = if (twoLineLearnerHeader) {
            canvas.top(top + headerHeight / 2f + 4f) - 0.36f * headerSize
        } else {
            canvas.top(top + headerHeight / 2f) - 0.36f * headerSize
        }
        canvas.tracked("STRAND", x + 14f, headerBaseline, headerFont, headerSize, headerSpacing, ReportTheme.PAPER)
        canvas.trackedR("AVERAGE", x + colStrand + colAverage - 14f, headerBaseline, headerFont, headerSize, headerSpacing, ReportTheme.PAPER)
        canvas.tracked("RECOMMENDATION", x + colStrand + colAverage + colLearners + 14f, headerBaseline, headerFont, headerSize, headerSpacing, ReportTheme.PAPER)
        if (twoLineLearnerHeader) {
            canvas.trackedR("LEARNERS NEEDING", x + colStrand + colAverage + colLearners - 14f, canvas.top(top + 12f), headerFont, headerSize, headerSpacing, ReportTheme.PAPER)
            canvas.trackedR("SUPPORT", x + colStrand + colAverage + colLearners - 14f, canvas.top(top + 23f), headerFont, headerSize, headerSpacing, ReportTheme.PAPER)
        } else {
            canvas.trackedR(learnersHeader, x + colStrand + colAverage + colLearners - 14f, headerBaseline, headerFont, headerSize, headerSpacing, ReportTheme.PAPER)
        }

        val rows = recommendations.take(shown)
        rows.forEachIndexed { index, strand ->
            val rowTop = top + headerHeight + index * rowHeight
            val rowY = canvas.top(rowTop + rowHeight)
            if (index % 2 == 1) canvas.fillRect(x, rowY, width, rowHeight, ReportTheme.MINT_SOFT)
            canvas.line(x, rowY, x + width, rowY, ReportTheme.LINE, 0.45f)
            canvas.fillRect(x, rowY + 8f, 2.6f, rowHeight - 16f, ReportTheme.AMBER)

            val centreY = canvas.top(rowTop + rowHeight / 2f)
            val spread = min(14f, rowHeight * 0.16f)
            val nameSize = if (rowHeight >= 64f) 9.2f else 9f
            canvas.text(
                canvas.ellipsis(strand.strandName, canvas.theme.sansBold, nameSize, colStrand - 30f),
                x + 14f,
                centreY + 3.4f,
                canvas.theme.sansBold,
                nameSize,
                ReportTheme.INK,
            )
            canvas.text(
                canvas.ellipsis(strand.strandCode, canvas.theme.sans, 6.6f, colStrand - 30f),
                x + 14f,
                centreY - 8f,
                canvas.theme.sans,
                6.6f,
                ReportTheme.MUTED,
            )

            val averageRight = x + colStrand + colAverage - 14f
            canvas.textR(
                ReportTheme.pct(strand.classAverage),
                averageRight,
                centreY + spread + 1.2f,
                canvas.theme.sansBlack,
                if (rowHeight >= 64f) 13f else 10.5f,
                ReportTheme.AMBER,
            )
            supportChip(canvas, averageRight, centreY - spread - 0.6f)

            val learnersRight = x + colStrand + colAverage + colLearners - 14f
            val support = learnersNeedingSupport(strand)
            canvas.textR(
                support?.toString() ?: "\u2014",
                learnersRight,
                centreY + spread + 1.2f,
                canvas.theme.sansBlack,
                if (rowHeight >= 64f) 13f else 10.5f,
                ReportTheme.NAVY,
            )
            val rated = ratingEntries(strand).size
            canvas.textR(
                when {
                    support == null -> "no learner ratings"
                    rated == 1 -> "of 1 rated learner"
                    rated > 1 -> "of " + rated + " rated learners"
                    else -> "learners with ratings"
                },
                learnersRight,
                centreY - 8f,
                canvas.theme.sans,
                6.4f,
                ReportTheme.MUTED,
            )

            val recommendationX = x + colStrand + colAverage + colLearners + 14f
            val recommendationWidth = colRecommendation - 28f
            val lines = wrapLines(canvas, strand.recommendation.orEmpty(), canvas.theme.sans, 8.4f, recommendationWidth, 2)
            val firstBaseline = centreY + if (lines.size == 1) 3.2f else 7.4f
            lines.forEachIndexed { lineIndex, line ->
                canvas.text(line, recommendationX, firstBaseline - lineIndex * 11.6f, canvas.theme.sans, 8.4f, ReportTheme.INK)
            }
        }

        val tableBottom = top + headerHeight + rows.size * rowHeight
        canvas.strokeRound(x, canvas.top(tableBottom), width, headerHeight + rows.size * rowHeight, 3f, ReportTheme.LINE, 0.7f)
        if (truncated) {
            canvas.text(
                "Showing $shown of " + recommendations.size + " weak-strand recommendations.",
                x,
                canvas.top(tableBottom + 11f),
                canvas.theme.sans,
                6.6f,
                ReportTheme.MUTED,
            )
            return tableBottom + 15f
        }
        return tableBottom
    }

    /** Derived key figures for the page: no new payload fields, every value captioned. */
    private fun keyFigures(
        canvas: ReportCanvas,
        report: CbcClassReportPayload,
        recommendations: List<CbcStrandMasteryPayload>,
        top: Float,
        width: Float,
        height: Float,
        tall: Boolean,
    ) {
        val x = 36f
        val y = canvas.top(top + height)
        canvas.box(x, y, width, height, 6f, ReportTheme.PAPER, ReportTheme.LINE, 0.8f)
        val meanWeak = recommendations.map { it.classAverage }.average()
        val flagged = flaggedLearners(recommendations)
        // Denominator = learners who actually have a rating in any weak strand; the class roster
        // can be much larger than the assessed set, and "1 of 20" would imply 19 were cleared (F49 honesty).
        val learnersTotal = recommendations.flatMap { it.studentBreakdown.keys }.distinct().size
        val cells = listOf(
            Triple(
                "BELOW TARGET",
                recommendations.size.toString() + " of " + report.strandMastery.size,
                "learning areas need support",
            ),
            Triple(
                "MEAN OF WEAK STRANDS",
                ReportTheme.pct(meanWeak),
                "average across the listed strands",
            ),
            Triple(
                "LEARNERS FLAGGED",
                when {
                    flagged == null -> "\u2014"
                    learnersTotal > 0 -> flagged.toString() + " of " + learnersTotal
                    else -> flagged.toString()
                },
                if (flagged == null) "no learner-level ratings recorded" else "below meeting in at least one strand",
            ),
        )
        val valueSize = if (tall) 21f else 13f
        val valueBaseline = if (tall) y + 38f else y + 27f
        val captionBaseline = if (tall) y + 15f else y + 13f
        val cellWidth = width / cells.size
        cells.forEachIndexed { index, (label, value, caption) ->
            val cellX = x + index * cellWidth
            if (index > 0) canvas.line(cellX, y + 10f, cellX, y + height - 10f, ReportTheme.LINE, 0.6f)
            canvas.tracked(label, cellX + 16f, y + height - 15f, canvas.theme.sansBold, 5.8f, 1.3f, ReportTheme.TEAL_DARK)
            canvas.text(
                canvas.ellipsis(value, canvas.theme.sansBlack, valueSize, cellWidth - 32f),
                cellX + 16f,
                valueBaseline,
                canvas.theme.sansBlack,
                valueSize,
                ReportTheme.NAVY,
            )
            canvas.text(
                canvas.ellipsis(caption, canvas.theme.sans, 6.4f, cellWidth - 32f),
                cellX + 16f,
                captionBaseline,
                canvas.theme.sans,
                6.4f,
                ReportTheme.MUTED,
            )
        }
    }

    /** Plain-language review note for a sparse recommendations page (one or two findings). */
    private fun followUp(canvas: ReportCanvas, weakCount: Int, top: Float, width: Float) {
        val x = 36f
        val y = canvas.top(top + FOLLOW_UP_HEIGHT)
        canvas.box(x, y, width, FOLLOW_UP_HEIGHT, 8f, ReportTheme.MINT_SOFT, ReportTheme.TEAL, 0.8f)
        canvas.tracked("REVIEW & FOLLOW-UP", 54f, y + FOLLOW_UP_HEIGHT - 15f, canvas.theme.sansBold, 5.9f, 1.3f, ReportTheme.TEAL_DARK)
        canvas.text(
            canvas.ellipsis(
                "Subject teachers review each recommendation with the class teacher and carry the agreed action into next term's focus.",
                canvas.theme.sans,
                8.6f,
                width - 300f,
            ),
            54f,
            y + 17f,
            canvas.theme.sans,
            8.6f,
            ReportTheme.INK,
        )
        ReportFurniture.cardChipRight(
            canvas,
            if (weakCount == 1) "1 STRAND BELOW TARGET" else weakCount.toString() + " STRANDS BELOW TARGET",
            x + width - 18f,
            y + FOLLOW_UP_HEIGHT / 2f,
        )
    }

    /** Exam Office stamp + official-record line (D5: never a personal signatory). */
    private fun signOff(canvas: ReportCanvas, contentWidth: Float) {
        val right = 36f + contentWidth
        ReportFurniture.horizontalStamp(canvas, right - 118f, canvas.top(SIGN_OFF_STAMP_TOP + 26f))
        canvas.trackedR("OFFICIAL SCHOOL RECORD", right, canvas.top(SIGN_OFF_STAMP_TOP - 9f), canvas.theme.sansBold, 5.4f, 1f, ReportTheme.MUTED)
        val footnoteWidth = contentWidth - 118f - 60f
        canvas.text(
            canvas.ellipsis(
                "LEARNERS NEEDING SUPPORT counts learners rated below meeting expectation (not ME or EE) in that learning area; denominators count learners with a rating there.",
                canvas.theme.sans,
                6.4f,
                footnoteWidth,
            ),
            36f,
            canvas.top(FIRST_FOOTNOTE_TOP),
            canvas.theme.sans,
            6.4f,
            ReportTheme.MUTED,
        )
        canvas.text(
            canvas.ellipsis(
                "A dash means no learner-level ratings were recorded for that learning area.",
                canvas.theme.sans,
                6.4f,
                footnoteWidth,
            ),
            36f,
            canvas.top(FIRST_FOOTNOTE_TOP + 11f),
            canvas.theme.sans,
            6.4f,
            ReportTheme.MUTED,
        )
    }

    private fun supportChip(canvas: ReportCanvas, rightX: Float, centreY: Float) {
        val label = "Needs Support"
        val chipWidth = canvas.textWidth(label, canvas.theme.sansBold, 6.2f) + 14f
        val chipHeight = 12f
        canvas.fillRound(rightX - chipWidth, centreY - chipHeight / 2f, chipWidth, chipHeight, chipHeight / 2f, ReportTheme.AMBER_SOFT)
        canvas.textC(label, rightX - chipWidth / 2f, centreY - 2.1f, canvas.theme.sansBold, 6.2f, ReportTheme.AMBER)
    }

    /**
     * Exact per-learner ratings for one strand. The server now supplies letter ratings
     * (EE/ME/AE/BE) in [CbcStrandMasteryPayload.studentRatings] so ME and AE never collapse
     * (F50); older payloads only carry the mastery words in studentBreakdown.
     */
    private fun ratingEntries(strand: CbcStrandMasteryPayload): Map<String, String> =
        strand.studentRatings.ifEmpty { strand.studentBreakdown }

    /**
     * Learners needing support in one strand, or null when the payload has no learner-level
     * ratings (the table prints a dash rather than inventing a zero).
     *
     * The class payload stores CbcAnalyticsService.masteryLevel words (MASTER, ADVANCED,
     * PROFICIENT, DEVELOPING, NOVICE) in studentBreakdown, while the report vocabulary is
     * EE/ME/AE/BE/DE. Both are recognised: anything that is not meeting/exceeding counts.
     */
    private fun learnersNeedingSupport(strand: CbcStrandMasteryPayload): Int? {
        val entries = ratingEntries(strand)
        if (entries.isEmpty()) return null
        return entries.values.count { needsSupport(it) }
    }

    private fun needsSupport(rating: String?): Boolean {
        val level = rating?.trim()?.uppercase().orEmpty()
        if (level.isEmpty()) return false
        return level != "EE" && level != "ME" && level != "MASTER" && level != "ADVANCED"
    }

    /** Distinct learners flagged in at least one weak strand; null when no breakdown exists at all. */
    private fun flaggedLearners(recommendations: List<CbcStrandMasteryPayload>): Int? {
        val maps = recommendations.map { ratingEntries(it) }.filter { it.isNotEmpty() }
        if (maps.isEmpty()) return null
        val flagged = mutableSetOf<String>()
        maps.forEach { breakdown ->
            breakdown.forEach { (studentId, rating) ->
                if (needsSupport(rating)) flagged.add(studentId)
            }
        }
        return flagged.size
    }

    /** Wraps to at most [maxLines] lines, ellipsising the remainder of the text. */
    private fun wrapLines(canvas: ReportCanvas, value: String, font: PDFont, size: Float, maxWidth: Float, maxLines: Int): List<String> {
        val lines = canvas.wrap(value, font, size, maxWidth)
        if (lines.size <= maxLines) return lines
        val kept = lines.take(maxLines).toMutableList()
        val tail = lines.drop(maxLines - 1).joinToString(" ")
        kept[kept.size - 1] = canvas.ellipsis(tail, font, size, maxWidth)
        return kept
    }

    // ------------------------------------------------------------------ primitives

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
        canvas.text(value, x + 52f, y + height - 38f, canvas.theme.sansBlack, size, ReportTheme.NAVY)
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

    private fun bar(canvas: ReportCanvas, x: Float, y: Float, width: Float, height: Float, fraction: Double, fill: Color) {
        canvas.fillRound(x, y, width, height, height / 2f, ReportTheme.mix(ReportTheme.LINE, ReportTheme.PAPER, 0.35f))
        val filled = (width * fraction.coerceIn(0.0, 1.0)).toFloat().coerceAtLeast(if (fraction > 0) height else 0f)
        if (filled > 0f) canvas.fillRound(x, y, filled, height, height / 2f, fill)
    }

    private fun prettyTerm(term: String): String {
        val trimmed = term.trim()
        if (trimmed.uppercase().startsWith("TERM_")) return "Term " + trimmed.substringAfter("TERM_").trim()
        return trimmed
    }

    private companion object {
        const val LANDSCAPE_WIDTH = 841.89f
        const val LANDSCAPE_HEIGHT = 595.28f

        /** Content must stop above this top-down offset so the sign-off block never collides. */
        const val BOTTOM_CONTENT_LIMIT = 482f

        /** Row floor for the recommendations table (average, chip and two-line recommendation fit). */
        const val MIN_TABLE_ROW_HEIGHT = 46f

        /** Figure cards under the table: tall for one to three findings, compact from four up. */
        const val TALL_FIGURES_HEIGHT = 92f
        const val SHORT_FIGURES_HEIGHT = 56f

        /** Plain-language review note shown only when the sheet has room left for it. */
        const val FOLLOW_UP_HEIGHT = 58f

        const val SIGN_OFF_STAMP_TOP = 508f
        const val FIRST_FOOTNOTE_TOP = 498f
    }
}
