package com.afrithecus.brainbox.api.report.render

import com.afrithecus.brainbox.api.report.ReportBranding
import com.afrithecus.brainbox.api.report.ReportProperties
import com.afrithecus.brainbox.api.report.ReportTitles
import com.afrithecus.brainbox.api.report.TemplateSpec
import com.afrithecus.brainbox.api.report.web.ReportType
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject
import org.springframework.stereotype.Component
import java.io.ByteArrayOutputStream

/**
 * Blank report templates (A11/F22): one branded v1 shell per report type with the report's
 * real section skeleton and headers, a pale "TEMPLATE" watermark and **no fabricated marks** -
 * only field labels and empty rows. Orientation follows ReportTitles.landscape, so the three
 * dense lists, the analysis, the class and teacher reports are landscape and the learner cards
 * are portrait.
 */
@Component
class TemplateRenderer(
    private val properties: ReportProperties = ReportProperties(),
) {

    private data class Section(
        val title: String,
        val headers: List<String>,
        /** Optional labels for the first column (identity fields); others stay blank. */
        val rowLabels: List<String> = emptyList(),
        val desiredRows: Int = 8,
    )

    fun render(spec: TemplateSpec): ByteArray {
        val landscape = ReportTitles.landscape(spec.reportType)
        return PDDocument().use { document ->
            document.documentInformation.title = spec.title
            val theme = ReportTheme(document)
            val logo = ReportImages.brandingLogo(document, spec.branding.logoUrl, properties.embedLogo)
            val width = if (landscape) 841.89f else 595.28f
            val height = if (landscape) 595.28f else 841.89f
            ReportCanvas(document, theme, width, height).use { canvas ->
                draw(canvas, spec, logo, landscape)
            }
            val out = ByteArrayOutputStream()
            document.save(out)
            out.toByteArray()
        }
    }

    private fun draw(canvas: ReportCanvas, spec: TemplateSpec, logo: PDImageXObject?, landscape: Boolean) {
        // A template carries the shell, not the school's confidential watermark: a pale
        // "TEMPLATE" mark replaces it and the footer says no school data is printed.
        canvas.watermark(
            "TEMPLATE",
            canvas.width / 2f,
            canvas.height / 2f + 20f,
            if (landscape) 74f else 84f,
            ReportTheme.mix(ReportTheme.NAVY, ReportTheme.PAPER, 0.94f),
        )
        val title = ReportTitles.title(spec.reportType)
        val offset = ReportFurniture.cardHeader(
            canvas,
            spec.branding,
            logo,
            "YYYY",
            "TERM",
            title,
            "Blank template  -  " + ReportTitles.description(spec.reportType),
            landscape = landscape,
        )
        val sections = sectionsFor(spec.reportType)
        val plan = plan(sections, canvas.height - offset - 58f)
        var cursor = offset + 8f
        sections.forEachIndexed { index, section ->
            cursor = drawSection(canvas, section, plan.rows[index], cursor, plan.rowHeight, plan.headerHeight, plan.headingHeight, plan.gap)
        }
        canvas.textC(
            "TEMPLATE - no school data is printed on this page.",
            canvas.width / 2f,
            canvas.top(canvas.height - 46f),
            canvas.theme.sansItalic,
            6.4f,
            ReportTheme.MUTED,
        )
        ReportFurniture.cardFooter(canvas, spec.branding, "Blank template", 1, 1)
    }

    // ------------------------------------------------------------- page plan

    private data class Plan(
        val rows: List<Int>,
        val rowHeight: Float,
        val headerHeight: Float,
        val headingHeight: Float,
        val gap: Float,
    )

    private fun plan(sections: List<Section>, available: Float): Plan {
        var rowHeight = 18f
        var headerHeight = 20f
        var headingHeight = 22f
        var gap = 12f
        fun total(rows: List<Int>): Float =
            sections.size * (headingHeight + headerHeight + gap) + rows.sum() * rowHeight + 6f
        var rows = sections.map { it.desiredRows }
        if (total(rows) > available) {
            rowHeight = 16f
            headerHeight = 18f
            headingHeight = 19f
            gap = 7f
        }
        val minTotal = sections.size * 2
        var room = ((available - sections.size * (headingHeight + headerHeight + gap) - 6f) / rowHeight).toInt()
        room = room.coerceAtLeast(minTotal)
        if (rows.sum() > room) {
            rows = sections.map { minOf(2, it.desiredRows) }
            var remaining = room - rows.sum()
            var progressed = true
            while (remaining > 0 && progressed) {
                progressed = false
                rows = rows.mapIndexed { index, count ->
                    if (remaining > 0 && count < sections[index].desiredRows) {
                        remaining -= 1
                        progressed = true
                        count + 1
                    } else {
                        count
                    }
                }
            }
        }
        return Plan(rows, rowHeight, headerHeight, headingHeight, gap)
    }

    // ------------------------------------------------------------- drawing

    private fun drawSection(
        canvas: ReportCanvas,
        section: Section,
        rows: Int,
        top: Float,
        rowHeight: Float,
        headerHeight: Float,
        headingHeight: Float,
        gap: Float,
    ): Float {
        val x = 36f
        val width = canvas.width - 72f
        var cursor = top
        val headingY = canvas.top(cursor + 8f)
        canvas.fillRect(x, headingY - 1.5f, 6f, 6f, ReportTheme.TEAL)
        canvas.tracked(section.title.uppercase(), x + 12f, headingY, canvas.theme.sansBold, 7f, 1.35f, ReportTheme.NAVY)
        canvas.line(x, canvas.top(cursor + headingHeight - 6f), x + width, canvas.top(cursor + headingHeight - 6f), ReportTheme.LINE, 0.6f)
        cursor += headingHeight

        val columns = section.headers.size.coerceAtLeast(1)
        val cellWidth = width / columns
        canvas.fillRound(x, canvas.top(cursor + headerHeight), width, headerHeight, 3f, ReportTheme.NAVY)
        section.headers.forEachIndexed { index, header ->
            val cx = x + cellWidth * index + (if (index == 0) 10f else cellWidth / 2f)
            if (index == 0) {
                canvas.tracked(header.uppercase(), cx, canvas.top(cursor + headerHeight / 2f) - 2.2f, canvas.theme.sansBold, 6f, 1.1f, ReportTheme.PAPER)
            } else {
                canvas.trackedC(header.uppercase(), cx, canvas.top(cursor + headerHeight / 2f) - 2.2f, canvas.theme.sansBold, 6f, 1.1f, ReportTheme.PAPER)
            }
        }
        cursor += headerHeight
        repeat(rows) { rowIndex ->
            val baseline = canvas.top(cursor + rowHeight / 2f) - 0.36f * 8f
            if (rowIndex % 2 == 1) canvas.fillRect(x, canvas.top(cursor + rowHeight), width, rowHeight, ReportTheme.MINT_SOFT)
            canvas.line(x, canvas.top(cursor + rowHeight), x + width, canvas.top(cursor + rowHeight), ReportTheme.LINE, 0.45f)
            val label = section.rowLabels.getOrNull(rowIndex).orEmpty()
            if (label.isNotBlank()) canvas.text(label, x + 10f, baseline, canvas.theme.sansBold, 8f, ReportTheme.INK)
            cursor += rowHeight
        }
        val tableTop = top + headingHeight
        canvas.strokeRound(x, canvas.top(cursor), width, cursor - tableTop, 3f, ReportTheme.LINE, 0.6f)
        return cursor + gap
    }

    // ------------------------------------------------------------- skeletons

    private fun sectionsFor(type: ReportType): List<Section> = when (type) {
        ReportType.CBC_STUDENT, ReportType.DETAILED_CBC_STUDENT -> buildList {
            add(Section("Student identity", listOf("FIELD", "VALUE"), listOf("Name", "Admission No.", "Grade", "Term", "Attendance"), 5))
            add(Section("CBC learning areas", listOf("LEARNING AREA", "EVIDENCE", "CODE", "LEVEL"), desiredRows = 7))
            add(Section("Paper exam results", listOf("SUBJECT", "SCORE", "MAX", "%", "LEVEL"), desiredRows = 8))
            if (type == ReportType.DETAILED_CBC_STUDENT) {
                add(Section("Engagement & focus (page 2)", listOf("METRIC", "VALUE"), listOf("Learning streak", "Total XP", "Class average", "Percentile"), 4))
            }
            add(Section("Overall competency", listOf("OVERALL STATUS"), rowLabels = listOf("Overall"), desiredRows = 1))
            add(Section("Teacher's comments", listOf("COMMENTS"), desiredRows = 3))
        }

        ReportType.CBC_CLASS, ReportType.DETAILED_CBC_CLASS -> buildList {
            add(Section("Class overview", listOf("CLASS", "CLASS TEACHER", "TERM", "OVERALL AVERAGE"), desiredRows = 1))
            add(Section("Strand mastery", listOf("LEARNING AREA", "CLASS AVERAGE", "STATUS"), desiredRows = 7))
            add(Section("Subject & teacher impact", listOf("SUBJECT", "TEACHER", "LEARNERS", "AVERAGE"), desiredRows = 8))
            if (type == ReportType.DETAILED_CBC_CLASS) {
                add(Section("Weak strand recommendations", listOf("STRAND", "AVERAGE", "RECOMMENDATION"), desiredRows = 4))
            }
        }

        ReportType.TRADITIONAL_STUDENT -> listOf(
            Section("Student identity", listOf("FIELD", "VALUE"), listOf("Name", "Admission No.", "Class", "Term", "Year"), 5),
            Section("Academic performance", listOf("SUBJECT", "SCORE", "MAX", "%", "LEVEL"), desiredRows = 8),
            Section("Overall achievement", listOf("OVERALL", "CLASS POSITION"), desiredRows = 1),
            Section("Class teacher's remarks", listOf("REMARKS"), desiredRows = 3),
        )

        ReportType.TRADITIONAL_COMBINED -> listOf(
            Section("Combined grade list", listOf("POS", "NAME", "SUBJECT SCORES", "TOTAL", "MEAN", "LEVEL"), desiredRows = 14),
            Section("Totals", listOf("SUBJECT MEANS", "GRADE MEAN", "RANKED", "INCOMPLETE"), desiredRows = 2),
            Section("Students with incomplete marks", listOf("POS", "NAME", "SUBJECT", "ZERO / MISSING"), desiredRows = 4),
        )

        ReportType.TRADITIONAL_PER_CLASS_TABLES -> listOf(
            Section("Class list", listOf("POS", "NAME", "SUBJECT SCORES", "TOTAL", "MEAN", "LEVEL"), desiredRows = 14),
            Section("Class totals", listOf("SUBJECT MEANS", "CLASS MEAN", "RANKED", "INCOMPLETE"), desiredRows = 2),
            Section("Students with incomplete marks (this class)", listOf("POS", "NAME", "SUBJECT", "ZERO / MISSING"), desiredRows = 4),
        )

        ReportType.TRADITIONAL_GRADE_ANALYSIS -> listOf(
            Section("Class comparison", listOf("CLASS", "CLASS TEACHER", "SUBJECT MEANS", "OVERALL MEAN", "POS"), desiredRows = 12),
            Section("Subject-teacher contribution", listOf("SUBJECT", "TEACHER", "LEARNERS", "MEAN", "REFERENCE"), desiredRows = 10),
        )

        ReportType.TEACHER_PERFORMANCE -> listOf(
            Section("Teacher identity", listOf("FIELD", "VALUE"), listOf("Teacher", "Class", "Term", "Year"), 4),
            Section("Subject delivery", listOf("SUBJECT", "LEARNERS", "AVERAGE", "LEVEL"), desiredRows = 8),
            Section("Strand impact", listOf("SUBJECT", "STRAND", "SCORE"), desiredRows = 10),
            Section("Teacher vs class", listOf("TEACHER AVERAGE", "CLASS AVERAGE", "COVERAGE", "LEARNERS"), desiredRows = 1),
        )
    }
}
