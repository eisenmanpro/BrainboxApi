package com.afrithecus.brainbox.api.content.figure

/**
 * Renders a [FigureSpec] to a self-contained, script-free SVG string. The model
 * supplies only data and labels; every element and attribute is constructed here,
 * text is XML-escaped, and every list is bounded, so the output cannot carry
 * active content or exceed a sane size.
 *
 * Rendering is pure and deterministic: the same spec always yields the same SVG,
 * which is what makes stored figures auditable and golden-testable.
 */
object DiagramRenderer {

    private const val WIDTH = 520
    private const val MARGIN = 24
    private const val MAX_CATEGORIES = 12
    private const val MAX_COLUMNS = 6
    private const val MAX_ROWS = 20
    private const val MAX_STEPS = 8
    private const val PRIMARY = "#2B4C7E"
    private const val LIGHT = "#E8EEF7"
    private const val INK = "#111827"
    private const val MUTED = "#4B5563"
    private const val GRID = "#CBD5E1"
    private const val WHITE = "#FFFFFF"
    private const val FONT = "Helvetica,Arial,sans-serif"

    fun render(spec: FigureSpec): String = when (spec.kind.trim().uppercase()) {
        "TABLE" -> table(spec)
        "BAR" -> bar(spec)
        "FLOW" -> flow(spec)
        else -> throw IllegalArgumentException("unsupported figure kind: " + spec.kind)
    }

    // ------------------------------------------------------------------ BAR

    private fun bar(spec: FigureSpec): String {
        val categories = spec.categories.orEmpty().take(MAX_CATEGORIES).map { clip(it, 14) }
        val values = spec.values.orEmpty().take(categories.size).map { if (it.isFinite()) it else 0.0 }
        val count = maxOf(categories.size, 1)
        val titleHeight = if (spec.title.isNullOrBlank()) 0 else 30
        val captionHeight = if (spec.caption.isNullOrBlank()) 0 else 24
        val plotTop = MARGIN + titleHeight
        val plotHeight = 200
        val plotBottom = plotTop + plotHeight
        val height = plotBottom + 50 + captionHeight
        val plotLeft = MARGIN + 42
        val plotRight = WIDTH - MARGIN
        val plotWidth = plotRight - plotLeft
        val max = niceCeil(values.maxOrNull() ?: 0.0)

        val body = StringBuilder()
        heading(spec.title, WIDTH / 2, 20, body)
        body.append(line(plotLeft, plotTop, plotLeft, plotBottom, GRID))
        body.append(line(plotLeft, plotBottom, plotRight, plotBottom, GRID))
        for (tick in 0..4) {
            val y = plotBottom - plotHeight * tick / 4
            body.append(line(plotLeft - 4, y, plotLeft, y, GRID))
            body.append(text(formatTick(max * tick / 4), plotLeft - 8, y + 4, 10, MUTED, "end", false, null))
        }
        val slot = plotWidth.toDouble() / count
        val barWidth = (slot * 0.58).toInt()
        categories.indices.forEach { index ->
            val value = values.getOrElse(index) { 0.0 }
            val barHeight = if (max <= 0.0) 0 else (value / max * plotHeight).toInt()
            val x = (plotLeft + slot * index + (slot - barWidth) / 2).toInt()
            body.append(rect(x, plotBottom - barHeight, barWidth, barHeight, PRIMARY, PRIMARY))
            body.append(
                text(clip(categories[index], 12), (plotLeft + slot * index + slot / 2).toInt(), plotBottom + 15, 10, INK, "middle", false, null),
            )
        }
        spec.xLabel?.takeIf { it.isNotBlank() }?.let {
            body.append(text(clip(it, 40), (plotLeft + plotRight) / 2, plotBottom + 34, 11, MUTED, "middle", false, null))
        }
        spec.yLabel?.takeIf { it.isNotBlank() }?.let {
            val centerY = plotTop + plotHeight / 2
            body.append(text(clip(it, 26), 14, centerY + 4, 11, MUTED, "middle", false, "rotate(-90 14 " + centerY + ")"))
        }
        subheading(spec.caption, WIDTH / 2, plotBottom + 50, body)
        return document(WIDTH, height, spec.title ?: spec.caption ?: "Bar chart", body.toString())
    }

    // ---------------------------------------------------------------- TABLE

    private fun table(spec: FigureSpec): String {
        val headers = spec.headers.orEmpty().take(MAX_COLUMNS).map { clip(it, 18) }
        val rows = spec.rows.orEmpty().take(MAX_ROWS).map { row -> row.take(maxOf(headers.size, 1)).map { clip(it, 18) } }
        val columns = maxOf(headers.size, 1)
        val titleHeight = if (spec.title.isNullOrBlank()) 0 else 30
        val captionHeight = if (spec.caption.isNullOrBlank()) 0 else 24
        val top = MARGIN + titleHeight
        val rowHeight = 26
        val height = top + rowHeight * (rows.size + 1) + 14 + captionHeight
        val tableWidth = WIDTH - 2 * MARGIN
        val columnWidth = tableWidth / columns

        val body = StringBuilder()
        heading(spec.title, WIDTH / 2, 20, body)
        body.append(rect(MARGIN, top, tableWidth, rowHeight, LIGHT, GRID))
        headers.forEachIndexed { index, header ->
            body.append(text(header, MARGIN + columnWidth * index + 8, top + 17, 11, INK, "start", true, null))
        }
        rows.forEachIndexed { rowIndex, row ->
            val y = top + rowHeight * (rowIndex + 1)
            body.append(rect(MARGIN, y, tableWidth, rowHeight, WHITE, GRID))
            row.forEachIndexed { columnIndex, cell ->
                body.append(text(cell, MARGIN + columnWidth * columnIndex + 8, y + 17, 11, INK, "start", false, null))
            }
        }
        subheading(spec.caption, WIDTH / 2, top + rowHeight * (rows.size + 1) + 14, body)
        return document(WIDTH, height, spec.title ?: "Table", body.toString())
    }

    // ----------------------------------------------------------------- FLOW

    private fun flow(spec: FigureSpec): String {
        val steps = spec.steps.orEmpty().take(MAX_STEPS).map { clip(it, 60) }
        val perRow = 4
        val rows = maxOf((steps.size + perRow - 1) / perRow, 1)
        val titleHeight = if (spec.title.isNullOrBlank()) 0 else 30
        val captionHeight = if (spec.caption.isNullOrBlank()) 0 else 24
        val top = MARGIN + titleHeight
        val boxHeight = 54
        val rowGap = 34
        val rail = if (spec.cyclic && steps.size > 1) 34 else 0
        val height = top + rows * boxHeight + (rows - 1) * rowGap + (if (rail > 0) rail + 6 else 8) + captionHeight
        val usable = WIDTH - 2 * MARGIN

        val body = StringBuilder()
        heading(spec.title, WIDTH / 2, 20, body)
        steps.forEachIndexed { index, step ->
            val row = index / perRow
            val column = index % perRow
            val inRow = minOf(perRow, steps.size - row * perRow)
            val slot = usable / maxOf(inRow, 1)
            val boxWidth = slot - 22
            val x = MARGIN + column * slot + 11
            val y = top + row * (boxHeight + rowGap)
            body.append(rect(x, y, boxWidth, boxHeight, WHITE, PRIMARY))
            wrappedText(step, x + boxWidth / 2, y + 20, boxWidth - 14, 11, body)
            val hasNext = index < steps.size - 1
            if (hasNext && column < inRow - 1) {
                arrowRight(x + boxWidth, y + boxHeight / 2, x + slot - 11, body)
            } else if (hasNext) {
                arrowDown(x + boxWidth / 2, y + boxHeight, (boxHeight + rowGap) - 8, body)
            }
        }
        if (spec.cyclic && steps.size > 1) {
            val lastIndex = steps.size - 1
            val lastRow = lastIndex / perRow
            val lastColumn = lastIndex % perRow
            val lastInRow = minOf(perRow, steps.size - lastRow * perRow)
            val lastSlot = usable / maxOf(lastInRow, 1)
            val lastX = MARGIN + lastColumn * lastSlot + 11 + (lastSlot - 22) / 2
            val lastY = top + lastRow * (boxHeight + rowGap) + boxHeight
            val railY = lastY + 20
            val firstX = MARGIN + 11 + (usable / maxOf(minOf(perRow, steps.size), 1) - 22) / 2
            body.append(line(lastX, lastY, lastX, railY, MUTED))
            body.append(line(lastX, railY, firstX, railY, MUTED))
            arrowUp(firstX, railY, top - 6, MUTED, body)
        }
        subheading(spec.caption, WIDTH / 2, height - 10, body)
        return document(WIDTH, height, spec.title ?: "Flow chart", body.toString())
    }

    // ---------------------------------------------------------- svg helpers

    private fun document(width: Int, height: Int, title: String, body: String): String =
        "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 " + width + " " + height +
            "\" width=\"" + width + "\" height=\"" + height + "\" role=\"img\"><title>" + esc(title) + "</title>" + body + "</svg>"

    private fun heading(value: String?, centerX: Int, y: Int, body: StringBuilder) {
        value?.takeIf { it.isNotBlank() }?.let { body.append(text(clip(it, 60), centerX, y, 14, INK, "middle", true, null)) }
    }

    private fun subheading(value: String?, centerX: Int, y: Int, body: StringBuilder) {
        value?.takeIf { it.isNotBlank() }?.let { body.append(text(clip(it, 90), centerX, y, 11, MUTED, "middle", false, null)) }
    }

    private fun text(
        content: String,
        x: Int,
        y: Int,
        size: Int,
        color: String,
        anchor: String,
        bold: Boolean,
        transform: String?,
    ): String {
        val transformAttribute = if (transform == null) "" else " transform=\"" + transform + "\""
        val weight = if (bold) " font-weight=\"bold\"" else ""
        return "<text x=\"" + x + "\" y=\"" + y + "\" font-size=\"" + size + "\" fill=\"" + color +
            "\" text-anchor=\"" + anchor + "\" font-family=\"" + FONT + "\"" + weight + transformAttribute + ">" +
            esc(content) + "</text>"
    }

    private fun rect(x: Int, y: Int, width: Int, height: Int, fill: String, stroke: String): String =
        "<rect x=\"" + x + "\" y=\"" + y + "\" width=\"" + maxOf(width, 1) + "\" height=\"" + maxOf(height, 1) +
            "\" rx=\"4\" fill=\"" + fill + "\" stroke=\"" + stroke + "\" stroke-width=\"1\"/>"

    private fun line(x1: Int, y1: Int, x2: Int, y2: Int, color: String): String =
        "<line x1=\"" + x1 + "\" y1=\"" + y1 + "\" x2=\"" + x2 + "\" y2=\"" + y2 +
            "\" stroke=\"" + color + "\" stroke-width=\"1\"/>"

    private fun arrowRight(x1: Int, y: Int, x2: Int, body: StringBuilder) {
        body.append(line(x1, y, x2, y, PRIMARY))
        body.append("<polygon points=\"" + (x2 - 7) + "," + (y - 4) + " " + x2 + "," + y + " " + (x2 - 7) + "," + (y + 4) + "\" fill=\"" + PRIMARY + "\"/>")
    }

    private fun arrowDown(x: Int, y1: Int, length: Int, body: StringBuilder) {
        val y2 = y1 + length
        body.append(line(x, y1, x, y2, PRIMARY))
        body.append("<polygon points=\"" + (x - 4) + "," + (y2 - 7) + " " + x + "," + y2 + " " + (x + 4) + "," + (y2 - 7) + "\" fill=\"" + PRIMARY + "\"/>")
    }

    private fun arrowUp(x: Int, y1: Int, y2: Int, color: String, body: StringBuilder) {
        body.append(line(x, y1, x, y2, color))
        body.append("<polygon points=\"" + (x - 4) + "," + (y2 + 7) + " " + x + "," + y2 + " " + (x + 4) + "," + (y2 + 7) + "\" fill=\"" + color + "\"/>")
    }

    private fun wrappedText(value: String, centerX: Int, firstY: Int, maxWidth: Int, size: Int, body: StringBuilder) {
        val maxChars = maxOf((maxWidth / (size * 0.56)).toInt(), 6)
        val lines = mutableListOf<String>()
        var current = ""
        value.split(" ").forEach { word ->
            val candidate = if (current.isEmpty()) word else current + " " + word
            if (candidate.length <= maxChars) {
                current = candidate
            } else {
                if (current.isNotEmpty()) lines += current
                current = word
            }
        }
        if (current.isNotEmpty()) lines += current
        lines.take(3).forEachIndexed { index, lineText ->
            body.append(text(lineText, centerX, firstY + index * (size + 2), size, INK, "middle", false, null))
        }
    }

    private fun niceCeil(value: Double): Double {
        if (value <= 0.0) return 1.0
        val magnitude = Math.pow(10.0, Math.floor(Math.log10(value)))
        val normalized = value / magnitude
        val step = when {
            normalized <= 1.0 -> 1.0
            normalized <= 2.0 -> 2.0
            normalized <= 5.0 -> 5.0
            else -> 10.0
        }
        return step * magnitude
    }

    private fun formatTick(value: Double): String =
        if (Math.abs(value - Math.round(value)) < 0.05) Math.round(value).toString()
        else String.format(java.util.Locale.US, "%.1f", value)

    private fun clip(value: String, max: Int): String =
        if (value.length <= max) value else value.substring(0, maxOf(max - 3, 1)) + "..."

    private fun esc(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")
}
