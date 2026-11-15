package com.afrithecus.brainbox.api.report.render

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDFont
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject
import org.apache.pdfbox.util.Matrix
import java.awt.Color
import java.io.IOException
import java.util.Locale

/**
 * Drawing primitives shared by the report renderers (plan A2). One canvas owns exactly
 * one page; a paginated renderer creates a canvas per page so it stays in control of the
 * page plan (the list documents compute their own page counts up front).
 *
 * Coordinates follow PDFBox: the origin is the bottom-left corner and `top(offset)`
 * converts a top-down offset. Text is baseline anchored, matching the print sheets.
 */
class ReportCanvas(
    val document: PDDocument,
    val theme: ReportTheme,
    val width: Float,
    val height: Float,
) : AutoCloseable {

    val page: PDPage = PDPage(PDRectangle(width, height))
    val stream: PDPageContentStream = PDPageContentStream(document, page)

    init {
        document.addPage(page)
        stream.setLineCapStyle(1)
        stream.setLineJoinStyle(1)
    }

    /** PDF y for a top-down offset (0 = top of page). */
    fun top(offset: Float): Float = height - offset

    override fun close() {
        stream.close()
    }

    // ------------------------------------------------------------------ colour

    fun fillInk(color: Color) {
        stream.setNonStrokingColor(color)
    }

    fun strokeInk(color: Color, lineWidth: Float) {
        stream.setStrokingColor(color)
        stream.setLineWidth(lineWidth)
    }

    // ------------------------------------------------------------------ shapes

    fun fillRect(x: Float, y: Float, rectWidth: Float, rectHeight: Float, color: Color) {
        fillInk(color)
        stream.addRect(x, y, rectWidth, rectHeight)
        stream.fill()
    }

    fun strokeRect(x: Float, y: Float, rectWidth: Float, rectHeight: Float, color: Color, lineWidth: Float) {
        strokeInk(color, lineWidth)
        stream.addRect(x, y, rectWidth, rectHeight)
        stream.stroke()
    }

    fun roundPath(x: Float, y: Float, rectWidth: Float, rectHeight: Float, radius: Float) {
        val k = 0.5523f * radius
        stream.moveTo(x + radius, y)
        stream.lineTo(x + rectWidth - radius, y)
        stream.curveTo(x + rectWidth - radius + k, y, x + rectWidth, y + radius - k, x + rectWidth, y + radius)
        stream.lineTo(x + rectWidth, y + rectHeight - radius)
        stream.curveTo(x + rectWidth, y + rectHeight - radius + k, x + rectWidth - radius + k, y + rectHeight, x + rectWidth - radius, y + rectHeight)
        stream.lineTo(x + radius, y + rectHeight)
        stream.curveTo(x + radius - k, y + rectHeight, x, y + rectHeight - radius + k, x, y + rectHeight - radius)
        stream.lineTo(x, y + radius)
        stream.curveTo(x, y + radius - k, x + radius - k, y, x + radius, y)
        stream.closePath()
    }

    fun fillRound(x: Float, y: Float, rectWidth: Float, rectHeight: Float, radius: Float, color: Color) {
        fillInk(color)
        roundPath(x, y, rectWidth, rectHeight, radius)
        stream.fill()
    }

    fun strokeRound(x: Float, y: Float, rectWidth: Float, rectHeight: Float, radius: Float, color: Color, lineWidth: Float) {
        strokeInk(color, lineWidth)
        roundPath(x, y, rectWidth, rectHeight, radius)
        stream.stroke()
    }

    fun box(x: Float, y: Float, rectWidth: Float, rectHeight: Float, radius: Float, fill: Color, stroke: Color?, lineWidth: Float) {
        fillRound(x, y, rectWidth, rectHeight, radius, fill)
        if (stroke != null) strokeRound(x, y, rectWidth, rectHeight, radius, stroke, lineWidth)
    }

    fun line(x1: Float, y1: Float, x2: Float, y2: Float, color: Color, lineWidth: Float) {
        strokeInk(color, lineWidth)
        stream.moveTo(x1, y1)
        stream.lineTo(x2, y2)
        stream.stroke()
    }

    fun dashedLine(x1: Float, y1: Float, x2: Float, y2: Float, color: Color, lineWidth: Float, dash: FloatArray = floatArrayOf(1.6f, 1.8f), phase: Float = 0f) {
        stream.saveGraphicsState()
        stream.setLineDashPattern(dash, phase)
        line(x1, y1, x2, y2, color, lineWidth)
        stream.restoreGraphicsState()
    }

    fun diamond(cx: Float, cy: Float, size: Float, color: Color) {
        fillInk(color)
        stream.moveTo(cx, cy + size)
        stream.lineTo(cx + size, cy)
        stream.lineTo(cx, cy - size)
        stream.lineTo(cx - size, cy)
        stream.closePath()
        stream.fill()
    }

    fun circle(cx: Float, cy: Float, radius: Float) {
        val k = 0.5523f * radius
        stream.moveTo(cx + radius, cy)
        stream.curveTo(cx + radius, cy + k, cx + k, cy + radius, cx, cy + radius)
        stream.curveTo(cx - k, cy + radius, cx - radius, cy + k, cx - radius, cy)
        stream.curveTo(cx - radius, cy - k, cx - k, cy - radius, cx, cy - radius)
        stream.curveTo(cx + k, cy - radius, cx + radius, cy - k, cx + radius, cy)
        stream.closePath()
    }

    fun ring(cx: Float, cy: Float, radius: Float, color: Color, lineWidth: Float) {
        strokeInk(color, lineWidth)
        circle(cx, cy, radius)
        stream.stroke()
    }

    fun star(cx: Float, cy: Float, outer: Float, color: Color) {
        val inner = outer * 0.42f
        fillInk(color)
        for (i in 0 until 10) {
            val angle = -Math.PI / 2 + i * Math.PI / 5
            val radius = if (i % 2 == 0) outer else inner
            val px = cx + (Math.cos(angle) * radius).toFloat()
            val py = cy + (Math.sin(angle) * radius).toFloat()
            if (i == 0) stream.moveTo(px, py) else stream.lineTo(px, py)
        }
        stream.closePath()
        stream.fill()
    }

    fun stars(x: Float, cy: Float, count: Int, size: Float, gap: Float, color: Color) {
        for (i in 0 until count) star(x + size + i * (size * 2 + gap), cy, size, color)
    }

    fun arc(cx: Float, cy: Float, radius: Float, startDeg: Float, endDeg: Float, color: Color, lineWidth: Float) {
        strokeInk(color, lineWidth)
        var first = true
        val step = if (endDeg > startDeg) 2f else -2f
        var angle = startDeg
        while (if (step > 0) angle <= endDeg else angle >= endDeg) {
            val rad = Math.toRadians(angle.toDouble())
            val px = cx + (Math.cos(rad) * radius).toFloat()
            val py = cy + (Math.sin(rad) * radius).toFloat()
            if (first) {
                stream.moveTo(px, py)
                first = false
            } else {
                stream.lineTo(px, py)
            }
            angle += step
        }
        stream.stroke()
    }

    /** Filled wave from x = 0 to page width, sitting on the bottom edge. */
    fun wave(base: Float, amplitude: Float, phase: Float, frequency: Float, color: Color) {
        fillInk(color)
        stream.moveTo(0f, base)
        var x = 3f
        while (x <= width + 3f) {
            val y = base + amplitude * Math.sin((x / width) * Math.PI * 2 * frequency + phase).toFloat()
            stream.lineTo(x, y)
            x += 3f
        }
        stream.lineTo(width, 0f)
        stream.lineTo(0f, 0f)
        stream.closePath()
        stream.fill()
    }

    /** Diagonal watermark in a very pale tint (3-4 % ink, plan A13). */
    fun watermark(label: String?, cx: Float, cy: Float, size: Float) {
        if (label.isNullOrBlank()) return
        stream.saveGraphicsState()
        stream.transform(Matrix.getRotateInstance(Math.toRadians(-28.0), cx, cy))
        textC(label, cx, cy, theme.sansBlack, size, ReportTheme.mix(ReportTheme.NAVY, ReportTheme.PAPER, 0.96f))
        stream.restoreGraphicsState()
    }

    fun image(image: PDImageXObject, x: Float, y: Float, w: Float, h: Float) {
        stream.drawImage(image, x, y, w, h)
    }

    // -------------------------------------------------------------------- text

    fun textWidth(value: String?, font: PDFont, size: Float): Float = theme.textWidth(font, value, size)

    fun trackedWidth(value: String?, font: PDFont, size: Float, spacing: Float): Float =
        theme.trackedWidth(value, font, size, spacing)

    fun text(value: String?, x: Float, y: Float, font: PDFont, size: Float, color: Color) {
        val safe = ReportTheme.sanitize(value)
        if (safe.isEmpty()) return
        showTextSafely(safe, x, y, font, size, color)
    }

    fun textC(value: String?, cx: Float, y: Float, font: PDFont, size: Float, color: Color) {
        text(value, cx - textWidth(value, font, size) / 2f, y, font, size, color)
    }

    fun textR(value: String?, rightX: Float, y: Float, font: PDFont, size: Float, color: Color) {
        text(value, rightX - textWidth(value, font, size), y, font, size, color)
    }

    /**
     * Letter-spaced caps. Uses the PDF character-spacing operator on one string instead of
     * drawing each glyph separately, so the extracted text stays a word ("TOTALS") rather
     * than "T O T A L S" (A14).
     */
    fun tracked(value: String?, x: Float, y: Float, font: PDFont, size: Float, spacing: Float, color: Color) {
        if (value.isNullOrEmpty()) return
        try {
            stream.setCharacterSpacing(spacing)
            text(value, x, y, font, size, color)
        } finally {
            stream.setCharacterSpacing(0f)
        }
    }

    fun trackedC(value: String?, cx: Float, y: Float, font: PDFont, size: Float, spacing: Float, color: Color) {
        tracked(value, cx - trackedWidth(value, font, size, spacing) / 2f, y, font, size, spacing, color)
    }

    fun trackedR(value: String?, rightX: Float, y: Float, font: PDFont, size: Float, spacing: Float, color: Color) {
        tracked(value, rightX - trackedWidth(value, font, size, spacing), y, font, size, spacing, color)
    }

    fun wrap(value: String?, font: PDFont, size: Float, maxWidth: Float): List<String> {
        if (value.isNullOrBlank()) return emptyList()
        val lines = mutableListOf<String>()
        val current = StringBuilder()
        for (word in ReportTheme.sanitize(value).trim().split(' ')) {
            val candidate = if (current.isEmpty()) word else "$current $word"
            if (textWidth(candidate, font, size) <= maxWidth) {
                current.setLength(0)
                current.append(candidate)
            } else {
                if (current.isNotEmpty()) lines.add(current.toString())
                current.setLength(0)
                current.append(word)
            }
        }
        if (current.isNotEmpty()) lines.add(current.toString())
        return lines
    }

    /** Ellipsises a single-line value to fit [maxWidth]. */
    fun ellipsis(value: String?, font: PDFont, size: Float, maxWidth: Float): String {
        if (value == null) return ""
        val safe = ReportTheme.sanitize(value)
        if (textWidth(safe, font, size) <= maxWidth) return safe
        var low = 1
        var high = safe.length
        while (low < high) {
            val mid = (low + high + 1) / 2
            if (textWidth(safe.substring(0, mid) + "\u2026", font, size) <= maxWidth) low = mid else high = mid - 1
        }
        return safe.substring(0, low) + "\u2026"
    }

    private fun showTextSafely(value: String, x: Float, y: Float, font: PDFont, size: Float, color: Color) {
        try {
            emit(value, x, y, font, size, color)
        } catch (error: IllegalArgumentException) {
            runCatching { stream.endText() }
            val ascii = ReportTheme.asciiFallback(value)
            if (ascii != value) runCatching { emit(ascii, x, y, font, size, color) }
        }
    }

    private fun emit(value: String, x: Float, y: Float, font: PDFont, size: Float, color: Color) {
        fillInk(color)
        stream.beginText()
        stream.setFont(font, size)
        stream.newLineAtOffset(x, y)
        stream.showText(value)
        stream.endText()
    }
}
