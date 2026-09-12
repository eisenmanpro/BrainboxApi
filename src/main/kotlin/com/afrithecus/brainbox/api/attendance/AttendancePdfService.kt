package com.afrithecus.brainbox.api.attendance

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDFont
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.springframework.stereotype.Service
import java.io.ByteArrayOutputStream

/** One row of the rendered attendance register. */
data class AttendancePdfRow(
    val studentName: String,
    val present: Int,
    val late: Int,
    val absent: Int,
    val excused: Int,
    val rate: Double,
)

/**
 * Renders a printable attendance register PDF (doc 04 §4.1 export-pdf) with
 * PDFBox, reusing the Apache-2.0 engine already on the classpath via the
 * spring-ai PDF reader. Standard-14 fonts keep the output small.
 */
@Service
class AttendancePdfService {

    fun render(
        className: String,
        range: String,
        rows: List<AttendancePdfRow>,
        averageAttendance: Double,
    ): ByteArray {
        PDDocument().use { document ->
            val font = PDType1Font(Standard14Fonts.FontName.HELVETICA)
            val bold = PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD)
            var page = newPage(document)
            var content = PDPageContentStream(document, page)
            var y = 790f

            fun text(face: PDFont, size: Float, x: Float, value: String) {
                content.beginText()
                content.setFont(face, size)
                content.newLineAtOffset(x, y)
                content.showText(sanitize(value))
                content.endText()
            }

            text(bold, 16f, 50f, "Attendance Report")
            y -= 22f
            text(font, 11f, 50f, className)
            y -= 15f
            text(font, 10f, 50f, range)
            y -= 15f
            text(font, 10f, 50f, "Average attendance: " + averageAttendance + "%")
            y -= 24f
            drawHeader(font, bold, ::text)
            y -= 18f

            if (rows.isEmpty()) {
                text(font, 10f, 50f, "No registers recorded in this range.")
            }
            for (row in rows) {
                if (y < 60f) {
                    content.close()
                    page = newPage(document)
                    content = PDPageContentStream(document, page)
                    y = 790f
                    text(bold, 14f, 50f, "Attendance Report (continued)")
                    y -= 24f
                    drawHeader(font, bold, ::text)
                    y -= 18f
                }
                text(font, 10f, 50f, row.studentName)
                text(font, 10f, 330f, row.present.toString())
                text(font, 10f, 370f, row.late.toString())
                text(font, 10f, 410f, row.absent.toString())
                text(font, 10f, 450f, row.excused.toString())
                text(font, 10f, 495f, row.rate.toString() + "%")
                y -= 16f
            }
            content.close()

            val out = ByteArrayOutputStream()
            document.save(out)
            return out.toByteArray()
        }
    }

    private fun drawHeader(font: PDFont, bold: PDFont, text: (PDFont, Float, Float, String) -> Unit) {
        text(bold, 10f, 50f, "Student")
        text(bold, 10f, 330f, "P")
        text(bold, 10f, 370f, "L")
        text(bold, 10f, 410f, "A")
        text(bold, 10f, 450f, "E")
        text(bold, 10f, 495f, "Rate")
    }

    private fun newPage(document: PDDocument): PDPage {
        val page = PDPage(PDRectangle.A4)
        document.addPage(page)
        return page
    }

    /** Standard-14 fonts are WinAnsi; drop anything they cannot encode. */
    private fun sanitize(value: String): String =
        value.map { if (it.code in 32..255) it else '?' }.joinToString("")
}
