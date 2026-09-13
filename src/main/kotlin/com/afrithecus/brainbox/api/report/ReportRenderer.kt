package com.afrithecus.brainbox.api.report

import com.afrithecus.brainbox.api.report.web.ReportType
import com.afrithecus.brainbox.api.traditional.model.displayName
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDFont
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject
import org.apache.pdfbox.util.Matrix
import org.springframework.stereotype.Component
import java.awt.Color
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.URI
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.math.roundToInt

/**
 * Server-side report renderer (PDFBox). Produces the "renderer of record" for the
 * report types the client hands to the server, applying the school branding so a
 * server PDF matches the on-device template (docs/ongoing/api_reports_changes.md
 * sections 1-2). Text is restricted to WinAnsi-safe glyphs [sanitize].
 */
@Component
class ReportRenderer(private val properties: ReportProperties) {

    fun render(spec: ReportRenderSpec): ByteArray {
        PDDocument().use { document ->
            when (spec) {
                is GradeTableSpec -> renderGradeTable(document, spec)
                is GradeAnalysisSpec -> renderGradeAnalysis(document, spec)
                is TraditionalStudentsSpec -> renderTraditionalStudents(document, spec)
                is CbcStudentsSpec -> renderCbcStudents(document, spec)
                is CbcClassSpec -> renderCbcClass(document, spec)
                is TemplateSpec -> renderTemplate(document, spec)
            }
            val out = ByteArrayOutputStream()
            document.save(out)
            return out.toByteArray()
        }
    }

    // ------------------------------------------------------------ grade tables

    private fun renderGradeTable(document: PDDocument, spec: GradeTableSpec) {
        val canvas = PdfCanvas(document, spec.branding, landscape = true, embedLogo = properties.embedLogo)
        val exam = spec.exam
        canvas.titleBlock(spec.title, subtitle(exam))
        if (spec.rows.isEmpty()) {
            canvas.text("No marks have been recorded for this exam yet.", canvas.margin, canvas.cursor, 10f)
            canvas.finish()
            return
        }
        val subjects = exam.subjects.map { it.name }
        if (spec.perClass) {
            spec.rows.groupBy { it.classTag }.toSortedMap().forEach { (classTag, rows) ->
                canvas.ensure(60f)
                canvas.sectionHeading(classTag + "  (" + rows.size + " students)")
                drawGradeTable(canvas, exam, subjects, rows, includeClass = false)
                canvas.cursor -= 18f
            }
        } else {
            drawGradeTable(canvas, exam, subjects, spec.rows, includeClass = true)
        }
        canvas.finish()
    }

    private fun drawGradeTable(
        canvas: PdfCanvas,
        exam: com.afrithecus.brainbox.api.traditional.web.TraditionalExamDto,
        subjects: List<String>,
        rows: List<com.afrithecus.brainbox.api.traditional.web.StudentGradeRowDto>,
        includeClass: Boolean,
    ) {
        val available = canvas.width - 2 * canvas.margin
        val fixed = mutableListOf<Pair<String, Float>>()
        fixed += "#" to 26f
        fixed += "Student" to 130f
        if (includeClass) fixed += "Class" to 96f
        subjects.forEach { fixed += it to 0f }
        fixed += "Total" to 46f
        fixed += "Grade" to 46f
        fixed += "Pos" to 34f
        val reserved = fixed.filter { it.second > 0f }.sumOf { it.second.toDouble() }.toFloat()
        val subjectWidth = if (subjects.isEmpty()) 0f else ((available - reserved) / subjects.size).coerceAtLeast(34f)
        val widths = fixed.map { if (it.second > 0f) it.second else subjectWidth }
        val headers = fixed.map { it.first }

        var lastHeaderPage = -1
        fun headerIfNeeded() {
            if (canvas.pageNumber != lastHeaderPage) {
                canvas.tableHeader(headers, widths)
                lastHeaderPage = canvas.pageNumber
            }
        }
        headerIfNeeded()
        rows.forEach { row ->
            canvas.ensure(TABLE_ROW_HEIGHT)
            headerIfNeeded()
            val cells = mutableListOf<String>()
            cells += row.rank.toString()
            cells += row.studentName
            if (includeClass) cells += row.classTag
            subjects.forEach { name ->
                val result = row.subjects.find { it.subjectName == name }
                cells += if (result == null) "-" else result.rawScore.toString() + " " + result.grade
            }
            cells += row.totalScore.toString()
            cells += row.overallGrade
            cells += row.rank.toString()
            canvas.tableRow(cells, widths, TABLE_ROW_FONT)
        }
    }

    // ---------------------------------------------------------- grade analysis

    private fun renderGradeAnalysis(document: PDDocument, spec: GradeAnalysisSpec) {
        val canvas = PdfCanvas(document, spec.branding, landscape = true, embedLogo = properties.embedLogo)
        val analysis = spec.analysis
        canvas.titleBlock(
            spec.title,
            analysis.gradeLevel + "  |  " + analysis.term + " " + analysis.year,
        )
        if (analysis.classes.isEmpty()) {
            canvas.text("No complete marks have been recorded for this exam yet.", canvas.margin, canvas.cursor, 10f)
            canvas.finish()
            return
        }
        val subjectKeys = analysis.classes.flatMap { it.subjectAverages.keys }.distinct()
        val available = canvas.width - 2 * canvas.margin
        val fixed = mutableListOf<Pair<String, Float>>()
        fixed += "Class" to 110f
        fixed += "Teacher" to 130f
        subjectKeys.forEach { fixed += it to 0f }
        fixed += "Average" to 56f
        fixed += "Pos" to 34f
        val reserved = fixed.filter { it.second > 0f }.sumOf { it.second.toDouble() }.toFloat()
        val subjectWidth = if (subjectKeys.isEmpty()) 0f else ((available - reserved) / subjectKeys.size).coerceAtLeast(40f)
        val widths = fixed.map { if (it.second > 0f) it.second else subjectWidth }
        val headers = fixed.map { it.first }

        var lastHeaderPage = -1
        fun headerIfNeeded() {
            if (canvas.pageNumber != lastHeaderPage) {
                canvas.tableHeader(headers, widths)
                lastHeaderPage = canvas.pageNumber
            }
        }
        headerIfNeeded()
        analysis.classes.forEach { row ->
            canvas.ensure(TABLE_ROW_HEIGHT)
            headerIfNeeded()
            val cells = mutableListOf<String>()
            cells += row.classTag
            cells += row.teacherName.ifBlank { "-" }
            subjectKeys.forEach { key ->
                val value = row.subjectAverages[key]
                cells += if (value == null) "-" else oneDecimal(value)
            }
            cells += oneDecimal(row.totalAverage)
            cells += row.overallPosition.toString()
            canvas.tableRow(cells, widths, TABLE_ROW_FONT)
        }
        canvas.finish()
    }

    // ------------------------------------------------------- student reports

    private fun renderTraditionalStudents(document: PDDocument, spec: TraditionalStudentsSpec) {
        val canvas = PdfCanvas(document, spec.branding, landscape = false, embedLogo = properties.embedLogo)
        canvas.titleBlock(spec.title, "Individual student reports")
        spec.reports.forEachIndexed { index, report ->
            if (index > 0) canvas.newPage()
            canvas.sectionHeading(report.studentName + "  (" + report.admissionNumber + ")")
            canvas.text(
                report.classTag + "  |  " + report.term + " " + report.year + "  |  " + report.gradeLevel,
                canvas.margin,
                canvas.cursor,
                9f,
                color = MUTED,
            )
            canvas.cursor -= 14f
            val widths = listOf(180f, 60f, 55f, 65f, 60f)
            canvas.tableHeader(listOf("Subject", "Score", "Max", "%", "Grade"), widths)
            report.subjectResults.forEach { subject ->
                canvas.ensure(TABLE_ROW_HEIGHT)
                canvas.tableRow(
                    listOf(
                        subject.subjectName,
                        subject.rawScore.toString(),
                        subject.maxScore.toString(),
                        oneDecimal(subject.percentage),
                        subject.grade,
                    ),
                    widths,
                    TABLE_ROW_FONT,
                )
            }
            canvas.ensure(60f)
            canvas.cursor -= 10f
            canvas.text("Total: " + report.totalScore + "   Overall: " + oneDecimal(report.overallPercentage) + "%   Grade: " + report.overallGrade, canvas.margin, canvas.cursor, 10f, bold = true)
            canvas.cursor -= 14f
            canvas.text("Position: " + report.classPosition + " of " + report.totalStudentsInClass, canvas.margin, canvas.cursor, 9f)
            canvas.cursor -= 14f
            val remarks = report.teacherRemarks?.takeIf { it.isNotBlank() }
            if (remarks != null) {
                canvas.text("Teacher remarks:", canvas.margin, canvas.cursor, 9f, bold = true)
                canvas.cursor -= 12f
                canvas.wrappedParagraph(remarks, canvas.margin, canvas.cursor, canvas.width - 2 * canvas.margin, 9f)
            }
        }
        canvas.finish()
    }

    private fun renderCbcStudents(document: PDDocument, spec: CbcStudentsSpec) {
        val canvas = PdfCanvas(document, spec.branding, landscape = false, embedLogo = properties.embedLogo)
        canvas.titleBlock(spec.title, "Competency-based curriculum reports")
        spec.cards.forEachIndexed { index, entry ->
            if (index > 0) canvas.newPage()
            val card = entry.card
            canvas.sectionHeading(entry.studentName)
            val meta = listOfNotNull(
                entry.gradeLevel?.takeIf { it.isNotBlank() },
                card.term.takeIf { it.isNotBlank() },
            ).joinToString("  |  ")
            if (meta.isNotEmpty()) {
                canvas.text(meta, canvas.margin, canvas.cursor, 9f, color = MUTED)
                canvas.cursor -= 14f
            }
            canvas.text(
                "Attendance: " + oneDecimal(card.attendancePercentage) + "%    Overall: " + card.overallGrade,
                canvas.margin,
                canvas.cursor,
                10f,
                bold = true,
            )
            canvas.cursor -= 16f
            if (card.strandRatings.isNotEmpty()) {
                val widths = listOf(150f, 250f, 70f)
                canvas.tableHeader(listOf("Strand", "Descriptor", "Rating"), widths)
                card.strandRatings.forEach { rating ->
                    canvas.ensure(TABLE_ROW_HEIGHT)
                    canvas.tableRow(
                        listOf(rating.strandCode, rating.descriptor, rating.rating),
                        widths,
                        TABLE_ROW_FONT,
                    )
                }
                canvas.cursor -= 12f
            }
            if (card.paperExamResults.isNotEmpty()) {
                canvas.ensure(50f)
                canvas.text("Paper exams", canvas.margin, canvas.cursor, 10f, bold = true)
                canvas.cursor -= 14f
                val widths = listOf(180f, 70f, 70f, 70f, 60f)
                canvas.tableHeader(listOf("Subject", "Score", "Max", "%", "Grade"), widths)
                card.paperExamResults.forEach { exam ->
                    canvas.ensure(TABLE_ROW_HEIGHT)
                    canvas.tableRow(
                        listOf(
                            exam.subject,
                            oneDecimal(exam.score),
                            oneDecimal(exam.maxScore),
                            oneDecimal(exam.percentage),
                            exam.grade,
                        ),
                        widths,
                        TABLE_ROW_FONT,
                    )
                }
                canvas.cursor -= 12f
            }
            if (card.teacherComments.isNotBlank()) {
                canvas.ensure(60f)
                canvas.text("Teacher comments", canvas.margin, canvas.cursor, 10f, bold = true)
                canvas.cursor -= 14f
                canvas.wrappedParagraph(card.teacherComments, canvas.margin, canvas.cursor, canvas.width - 2 * canvas.margin, 9f)
            }
        }
        canvas.finish()
    }

    // ----------------------------------------------------- CBC class reports

    private fun renderCbcClass(document: PDDocument, spec: CbcClassSpec) {
        val canvas = PdfCanvas(document, spec.branding, landscape = false, embedLogo = properties.embedLogo)
        val report = spec.classReport
        canvas.titleBlock(spec.title, report.className + "  |  " + report.term)
        canvas.text(
            "Overall class average: " + oneDecimal(report.overallClassAverage) + "%",
            canvas.margin,
            canvas.cursor,
            10f,
            bold = true,
        )
        canvas.cursor -= 16f
        if (report.strandMastery.isNotEmpty()) {
            canvas.sectionHeading("Strand mastery")
            val widths = listOf(150f, 60f, 50f, 250f)
            canvas.tableHeader(listOf("Strand", "Average", "Weak", "Recommendation"), widths)
            report.strandMastery.forEach { strand ->
                canvas.ensure(TABLE_ROW_HEIGHT)
                canvas.tableRow(
                    listOf(
                        strand.strandName,
                        oneDecimal(strand.classAverage),
                        if (strand.isWeakStrand) "Yes" else "No",
                        strand.recommendation.orEmpty(),
                    ),
                    widths,
                    TABLE_ROW_FONT,
                )
            }
            canvas.cursor -= 12f
        }
        val performance = report.subjectTeacherPerformance
            .filter { spec.focusTeacher == null || it.teacherName.equals(spec.focusTeacher, ignoreCase = true) }
        if (performance.isNotEmpty()) {
            canvas.ensure(50f)
            canvas.sectionHeading("Subject performance")
            val widths = listOf(150f, 170f, 90f, 80f)
            canvas.tableHeader(listOf("Subject", "Teacher", "Class Avg", "Students"), widths)
            performance.forEach { row ->
                canvas.ensure(TABLE_ROW_HEIGHT)
                canvas.tableRow(
                    listOf(row.subject, row.teacherName, oneDecimal(row.classAverage), row.studentCount.toString()),
                    widths,
                    TABLE_ROW_FONT,
                )
            }
        }
        canvas.finish()
    }

    // ---------------------------------------------------------- blank templates

    private fun renderTemplate(document: PDDocument, spec: TemplateSpec) {
        val landscape = when (spec.reportType) {
            ReportType.TRADITIONAL_COMBINED,
            ReportType.TRADITIONAL_PER_CLASS_TABLES,
            ReportType.TRADITIONAL_GRADE_ANALYSIS -> true
            else -> false
        }
        val canvas = PdfCanvas(document, spec.branding, landscape = landscape, embedLogo = properties.embedLogo)
        canvas.titleBlock(spec.title, "Blank template - complete the sections below with student and school data")
        templateSections(spec.reportType).forEach { blankTable(canvas, it) }
        canvas.finish()
    }

    private fun blankTable(canvas: PdfCanvas, section: TemplateSection) {
        canvas.ensure(52f)
        canvas.sectionHeading(section.title)
        val widths = evenWidths(canvas, section.headers.size)
        var headerPage = -1
        fun headerIfNeeded() {
            if (canvas.pageNumber != headerPage) {
                canvas.tableHeader(section.headers, widths)
                headerPage = canvas.pageNumber
            }
        }
        headerIfNeeded()
        val rowCount = if (section.rowLabels.isEmpty()) section.rowCount else section.rowLabels.size
        repeat(rowCount) { index ->
            canvas.ensure(TABLE_ROW_HEIGHT)
            headerIfNeeded()
            val cells = MutableList(section.headers.size) { "" }
            if (section.rowLabels.isNotEmpty()) cells[0] = section.rowLabels[index]
            canvas.tableRow(cells, widths, TABLE_ROW_FONT)
        }
        canvas.cursor -= 10f
    }

    private fun evenWidths(canvas: PdfCanvas, columns: Int): List<Float> {
        val count = columns.coerceAtLeast(1)
        return List(count) { (canvas.width - 2 * canvas.margin) / count }
    }

    private fun templateSections(type: ReportType): List<TemplateSection> = when (type) {
        ReportType.CBC_STUDENT, ReportType.DETAILED_CBC_STUDENT -> listOf(
            TemplateSection("Student Identity", listOf("Field", "Value"), listOf("Name", "Admission No", "Class", "Term", "Attendance")),
            TemplateSection("CBC Learning Areas", listOf("Learning Area", "Competency Level"), CBC_STRAND_NAMES),
            TemplateSection("Paper Exam Results", listOf("Subject", "Score", "Max", "Grade"), rowCount = 8),
            TemplateSection("Overall Competency", listOf("Overall Status"), rowCount = 1),
            TemplateSection("Teacher's Remarks", listOf("Remarks"), rowCount = 3),
        )
        ReportType.CBC_CLASS, ReportType.DETAILED_CBC_CLASS -> buildList {
            add(TemplateSection("Class Overview", listOf("Class", "Term", "Overall Average"), listOf("Class Name", "Term", "Average %")))
            add(TemplateSection("Strand Mastery", listOf("Strand", "Class Average"), CBC_STRAND_NAMES))
            add(TemplateSection("Teacher Subject Impact", listOf("Subject", "Teacher", "Students", "Average"), rowCount = 8))
            if (type == ReportType.DETAILED_CBC_CLASS) {
                add(TemplateSection("Weak Strand Recommendations", listOf("Strand", "Recommendation"), rowCount = 4))
            }
        }
        ReportType.TRADITIONAL_STUDENT -> listOf(
            TemplateSection("Student Identity", listOf("Field", "Value"), listOf("Name", "Admission No", "Class", "Term", "Year")),
            TemplateSection("Subject Results", listOf("Subject", "Score", "Max", "%", "Grade"), rowCount = 8),
            TemplateSection("Teacher's Remarks", listOf("Remarks"), rowCount = 3),
        )
        ReportType.TRADITIONAL_COMBINED -> listOf(
            TemplateSection("Combined", listOf("No", "NAME", "Subject", "Total", "Grade"), rowCount = 12),
        )
        ReportType.TRADITIONAL_PER_CLASS_TABLES -> listOf(
            TemplateSection("Class Tables", listOf("No", "NAME", "Subject 1", "Subject 2", "Total", "Grade"), rowCount = 12),
        )
        ReportType.TRADITIONAL_GRADE_ANALYSIS -> listOf(
            TemplateSection("Grade Analysis", listOf("Class Teacher", "Class", "Subject 1", "Subject 2", "Total", "Pos"), rowCount = 10),
        )
        ReportType.TEACHER_PERFORMANCE -> listOf(
            TemplateSection("Teacher Identity", listOf("Field", "Value"), listOf("Teacher Name", "Class", "Term")),
            TemplateSection("Subjects Taught", listOf("Subject", "Students", "Class Average"), rowCount = 8),
            TemplateSection("Strand Impact", listOf("Strand", "Score"), CBC_STRAND_NAMES),
        )
    }

    private data class TemplateSection(
        val title: String,
        val headers: List<String>,
        val rowLabels: List<String> = emptyList(),
        val rowCount: Int = 8,
    )

    // ------------------------------------------------------------ helpers

    private fun subtitle(exam: com.afrithecus.brainbox.api.traditional.web.TraditionalExamDto): String =
        exam.title + "  |  " + exam.gradeLevel + "  |  " + exam.term.displayName() + " " + exam.year

    private fun oneDecimal(value: Double): String {
        val rounded = (value * 10.0).roundToInt()
        return (rounded / 10).toString() + "." + (Math.abs(rounded % 10)).toString()
    }

    private companion object {
        const val TABLE_ROW_HEIGHT = 16f
        const val TABLE_ROW_FONT = 8f
        val MUTED = Color(0x60, 0x6B, 0x80)
        /** CBC strand names used by blank templates; mirrors the client's CbcStrands.NAMES. */
        val CBC_STRAND_NAMES: List<String> = listOf(
            "Communication & Collaboration",
            "Critical Thinking & Problem Solving",
            "Imagination & Creativity",
            "Citizenship",
            "Digital Literacy",
            "Learning to Learn",
            "Self-Efficacy",
        )
    }
}

/**
 * Minimal paginated PDF canvas: branded header/footer, watermark and table
 * primitives. Every page repeats the header so a long table stays readable.
 */
private class PdfCanvas(
    private val document: PDDocument,
    private val branding: ReportBranding,
    private val landscape: Boolean,
    private val embedLogo: Boolean,
) {
    private var page: PDPage
    private var stream: PDPageContentStream
    var cursor = 0f
    private var pageIndex = 1
    private var topOfContent = 0f
    private val regular: PDFont = PDType1Font(Standard14Fonts.FontName.HELVETICA)
    private val bold: PDFont = PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD)
    private val oblique: PDFont = PDType1Font(Standard14Fonts.FontName.HELVETICA_OBLIQUE)

    val margin = 36f
    private val headerHeight = 62f
    private val footerZone = 26f

    val width: Float get() = page.mediaBox.width
    val height: Float get() = page.mediaBox.height
    val pageNumber: Int get() = pageIndex

    init {
        page = createPage()
        stream = PDPageContentStream(document, page)
        beginPage()
    }

    private fun pageSize(): PDRectangle =
        if (landscape) PDRectangle(PDRectangle.A4.height, PDRectangle.A4.width) else PDRectangle.A4

    private fun createPage(): PDPage {
        val created = PDPage(pageSize())
        document.addPage(created)
        return created
    }

    private fun beginPage() {
        val primary = parseColor(branding.primaryColor, Color(0x1F, 0x2A, 0x44))
        filledRect(0f, height - headerHeight, width, headerHeight, primary)
        val onPrimary = if (luminance(primary) > 0.6) Color.BLACK else Color.WHITE
        var textX = margin
        if (embedLogo && !branding.logoUrl.isNullOrBlank()) {
            val logo = loadLogo(branding.logoUrl!!)
            if (logo != null) {
                runCatching { stream.drawImage(logo, margin, height - headerHeight + 9f, 44f, 44f) }
                textX = margin + 54f
            }
        }
        text(branding.schoolName.ifBlank { "BrainBox" }, textX, height - 26f, 16f, bold = true, color = onPrimary)
        branding.motto?.takeIf { it.isNotBlank() }?.let {
            text(it, textX, height - 40f, 9f, font = oblique, color = onPrimary)
        }
        val contact = listOfNotNull(
            branding.phone?.takeIf { it.isNotBlank() },
            branding.email?.takeIf { it.isNotBlank() },
            branding.address?.takeIf { it.isNotBlank() },
        ).joinToString("  |  ")
        if (contact.isNotEmpty()) text(contact, textX, height - 53f, 8f, color = onPrimary)
        drawWatermark()
        topOfContent = height - headerHeight - 14f
        cursor = topOfContent
        drawFooter()
    }

    private fun drawWatermark() {
        val mark = branding.watermarkText?.takeIf { it.isNotBlank() } ?: branding.schoolName.takeIf { it.isNotBlank() } ?: return
        stream.saveGraphicsState()
        stream.transform(Matrix.getRotateInstance(Math.toRadians(40.0), width / 2f - 150f, height / 2f - 40f))
        text(mark, 0f, 0f, 52f, bold = true, color = Color(0xE8, 0xEB, 0xF0))
        stream.restoreGraphicsState()
    }

    private fun drawFooter() {
        hLine(margin, width - margin, margin + footerZone - 14f, Color(0xD0, 0xD5, 0xDD), 0.5f)
        text(branding.schoolName.ifBlank { "BrainBox" }, margin, margin + footerZone - 24f, 7.5f, color = Color(0x80, 0x8A, 0x9A))
        val label = "Page " + pageIndex
        text(label, width - margin - fontWidth(bold, label, 7.5f), margin + footerZone - 24f, 7.5f, bold = true, color = Color(0x80, 0x8A, 0x9A))
    }

    fun titleBlock(title: String, subtitle: String?) {
        text(title, margin, cursor, 16f, bold = true, color = Color(0x1F, 0x2A, 0x44))
        cursor -= 15f
        if (!subtitle.isNullOrBlank()) {
            text(subtitle, margin, cursor, 9.5f, color = Color(0x60, 0x6B, 0x80))
            cursor -= 12f
        }
        hLine(margin, width - margin, cursor, Color(0xD0, 0xD5, 0xDD), 1f)
        cursor -= 16f
    }

    fun sectionHeading(label: String) {
        ensure(30f)
        text(label, margin, cursor, 11f, bold = true, color = Color(0x1F, 0x2A, 0x44))
        cursor -= 14f
    }

    fun newPage() {
        stream.close()
        page = createPage()
        stream = PDPageContentStream(document, page)
        pageIndex++
        beginPage()
    }

    fun ensure(space: Float) {
        if (cursor - space < margin + footerZone) newPage()
    }

    fun tableHeader(headers: List<String>, widths: List<Float>) {
        ensure(TABLE_HEADER_HEIGHT + 2f)
        val rowHeight = TABLE_HEADER_HEIGHT
        val y = cursor - rowHeight
        filledRect(margin, y, widths.sum(), rowHeight, Color(0xEE, 0xF1, 0xF6))
        var x = margin
        headers.forEachIndexed { index, header ->
            text(header, x + 3f, y + 5f, 8f, bold = true, color = Color(0x1F, 0x2A, 0x44))
            x += widths[index]
        }
        hLine(margin, margin + widths.sum(), y, Color(0xC4, 0xCB, 0xD6), 0.7f)
        cursor = y
    }

    fun tableRow(cells: List<String>, widths: List<Float>, fontSize: Float) {
        val rowHeight = TABLE_ROW_HEIGHT
        ensure(rowHeight)
        val y = cursor - rowHeight
        var x = margin
        cells.forEachIndexed { index, cell ->
            val cellWidth = widths.getOrElse(index) { 40f }
            text(cell, x + 3f, y + 5f, fontSize, color = Color(0x33, 0x3B, 0x4A))
            x += cellWidth
        }
        hLine(margin, margin + widths.sum(), y, Color(0xE6, 0xE9, 0xEF), 0.4f)
        cursor = y
    }

    fun wrappedParagraph(value: String, x: Float, y: Float, maxWidth: Float, fontSize: Float) {
        val words = sanitize(value).split(' ').filter { it.isNotBlank() }
        var line = StringBuilder()
        var baseline = y
        words.forEach { word ->
            val candidate = if (line.isEmpty()) word else line.toString() + " " + word
            if (fontWidth(regular, candidate, fontSize) > maxWidth && line.isNotEmpty()) {
                ensure(fontSize + 4f)
                text(line.toString(), x, baseline, fontSize, color = Color(0x33, 0x3B, 0x4A))
                baseline -= fontSize + 3f
                line = StringBuilder(word)
            } else {
                line = StringBuilder(candidate)
            }
        }
        if (line.isNotEmpty()) {
            ensure(fontSize + 4f)
            text(line.toString(), x, baseline, fontSize, color = Color(0x33, 0x3B, 0x4A))
            baseline -= fontSize + 3f
        }
        cursor = baseline
    }

    fun text(value: String?, x: Float, y: Float, size: Float, bold: Boolean = false, font: PDFont? = null, color: Color = Color.BLACK) {
        val safe = sanitize(value ?: "")
        if (safe.isEmpty()) return
        stream.beginText()
        stream.setFont(font ?: if (bold) this.bold else regular, size)
        stream.setNonStrokingColor(color)
        stream.newLineAtOffset(x, y)
        stream.showText(safe)
        stream.endText()
    }

    fun filledRect(x: Float, y: Float, w: Float, h: Float, color: Color) {
        stream.setNonStrokingColor(color)
        stream.addRect(x, y, w, h)
        stream.fill()
    }

    fun hLine(x1: Float, x2: Float, y: Float, color: Color, lineWidth: Float) {
        stream.setStrokingColor(color)
        stream.setLineWidth(lineWidth)
        stream.moveTo(x1, y)
        stream.lineTo(x2, y)
        stream.stroke()
    }

    fun finish() {
        stream.close()
    }

    private fun loadLogo(url: String): PDImageXObject? {
        val bytes = runCatching { fetch(url) }.getOrNull() ?: return null
        val image = runCatching { ImageIO.read(ByteArrayInputStream(bytes)) }.getOrNull() ?: return null
        return runCatching { LosslessFactory.createFromImage(document, image) }.getOrNull()
    }

    private fun fetch(url: String): ByteArray {
        if (url.startsWith("data:", ignoreCase = true)) {
            val comma = url.indexOf(',')
            if (comma < 0) return ByteArray(0)
            val payload = url.substring(comma + 1)
            return if (url.substring(0, comma).contains("base64", ignoreCase = true)) {
                Base64.getDecoder().decode(payload)
            } else {
                payload.toByteArray(Charsets.ISO_8859_1)
            }
        }
        return URI(url).toURL().openConnection().apply {
            connectTimeout = 2500
            readTimeout = 2500
        }.getInputStream().use { it.readBytes() }
    }

    private fun fontWidth(font: PDFont, value: String, size: Float): Float =
        runCatching { font.getStringWidth(sanitize(value)) / 1000f * size }.getOrDefault(0f)

    private fun sanitize(value: String): String {
        val out = StringBuilder(value.length)
        for (ch in value) {
            val code = ch.code
            when {
                code == 0x2026 -> out.append("...")
                code in 0x2018..0x201B -> out.append(39.toChar())
                code in 0x201C..0x201F -> out.append(34.toChar())
                code == 0x2013 || code == 0x2014 || code == 0x2212 -> out.append('-')
                code == 0x00A0 -> out.append(' ')
                code == 10 || code == 13 || code == 9 -> out.append(' ')
                code in 0x20..0xFF -> out.append(ch)
                else -> Unit
            }
        }
        return out.toString()
    }

    private fun parseColor(hex: String?, fallback: Color): Color {
        val value = hex?.trim()?.removePrefix("#") ?: return fallback
        if (value.length != 6) return fallback
        return runCatching { Color(value.toInt(16)) }.getOrDefault(fallback)
    }

    private fun luminance(color: Color): Double =
        (0.299 * color.red + 0.587 * color.green + 0.114 * color.blue) / 255.0

    private companion object {
        const val TABLE_HEADER_HEIGHT = 18f
        const val TABLE_ROW_HEIGHT = 16f
    }
}
