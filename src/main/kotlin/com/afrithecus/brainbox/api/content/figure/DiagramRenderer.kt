package com.afrithecus.brainbox.api.content.figure

/**
 * Renders a [FigureSpec] to a self-contained, script-free SVG. The model supplies
 * only data and labels; every element and attribute is constructed here, text is
 * XML-escaped, and every list is bounded, so the output cannot carry active
 * content or exceed a sane size.
 *
 * Visual quality is a contract, not an accident: one fixed palette, one type
 * scale, one margin system and one set of chart conventions are shared by every
 * kind, so the same data looks the same wherever it is rendered. Rendering is
 * pure and deterministic, which is what makes stored figures auditable and
 * golden-testable.
 */
object DiagramRenderer {

    private const val WIDTH = 640
    private const val MARGIN = 28.0

    // Design tokens.
    private const val INK = "#0F172A"
    private const val MUTED = "#64748B"
    private const val GRID = "#E2E8F0"
    private const val AXIS = "#94A3B8"
    private const val SURFACE = "#F8FAFC"
    private const val PRIMARY = "#2563EB"
    private const val PRIMARY_SOFT = "#DBEAFE"
    private const val WHITE = "#FFFFFF"
    private const val ACCENT = "#DC2626"
    private val SERIES = listOf("#2563EB", "#DC2626", "#059669", "#D97706", "#7C3AED", "#0891B2")

    private const val TITLE_SIZE = 16.0
    private const val CAPTION_SIZE = 12.0
    private const val LABEL_SIZE = 12.0
    private const val TICK_SIZE = 11.0

    // Bounds that keep a single figure readable and auditable.
    private const val MAX_CATEGORIES = 12
    private const val MAX_COLUMNS = 6
    private const val MAX_ROWS = 20
    private const val MAX_STEPS = 8
    private const val MAX_SERIES = 4
    private const val MAX_SLICES = 8
    private const val MAX_MARKS = 8
    private const val MAX_INTERVALS = 3
    private const val MAX_ELEMENTS = 40
    private const val MAX_TICKS = 40
    private const val MAX_TREE_NODES = 18
    private const val MAX_TREE_DEPTH = 6
    private const val MAX_SET_ITEMS = 4

    fun render(spec: FigureSpec): String = when (spec.kind.trim().uppercase()) {
        "TABLE" -> table(spec)
        "BAR" -> bar(spec)
        "LINE" -> line(spec)
        "PIE" -> pie(spec)
        "FLOW" -> flow(spec)
        "NUMBER_LINE" -> numberLine(spec)
        "GEOMETRY" -> geometry(spec)
        "TREE" -> tree(spec)
        "VENN" -> venn(spec)
        else -> throw IllegalArgumentException("unsupported figure kind: " + spec.kind)
    }

    // ------------------------------------------------------------------ BAR

    private fun bar(spec: FigureSpec): String {
        val categories = spec.categories.orEmpty().take(MAX_CATEGORIES).map { clip(it, 12) }
        val values = spec.values.orEmpty().take(categories.size).map { if (it.isFinite()) it else 0.0 }
        val count = maxOf(categories.size, 1)
        val top = MARGIN + titleHeight(spec)
        val plotHeight = 240.0
        val plotBottom = top + plotHeight
        val plotLeft = MARGIN + 54.0
        val plotRight = WIDTH - MARGIN
        val plotWidth = plotRight - plotLeft
        val footer = if (spec.xLabel.isNullOrBlank()) 42.0 else 56.0
        val height = (plotBottom + footer + captionHeight(spec)).toInt()
        val (yMin, yMax, _) = yBounds(values)

        val c = SvgCanvas(WIDTH, height, spec.title ?: spec.caption ?: "Bar chart")
        heading(c, spec)
        for (tick in 0..4) {
            val y = plotBottom - plotHeight * tick / 4.0
            c.line(plotLeft, y, plotRight, y, if (tick == 0) AXIS else GRID, 1.0)
            c.text(svgNum(yMin + (yMax - yMin) * tick / 4.0), plotLeft - 10.0, y + 4.0, TICK_SIZE, MUTED, "end")
        }
        c.line(plotLeft, top, plotLeft, plotBottom, AXIS, 1.0)

        val slot = plotWidth / count
        val barWidth = minOf(slot * 0.6, 60.0)
        categories.indices.forEach { index ->
            val value = values.getOrElse(index) { 0.0 }
            val fraction = if (yMax <= yMin) 0.0 else (value - yMin) / (yMax - yMin)
            val barHeight = (fraction * plotHeight).coerceIn(0.0, plotHeight)
            val x = plotLeft + slot * index + (slot - barWidth) / 2.0
            c.rect(x, plotBottom - barHeight, barWidth, barHeight, SERIES[0], SERIES[0], 3.0)
            if (spec.showValues) {
                c.text(svgNum(value), x + barWidth / 2.0, plotBottom - barHeight - 6.0, TICK_SIZE, INK, "middle")
            }
            c.text(categories[index], plotLeft + slot * index + slot / 2.0, plotBottom + 18.0, TICK_SIZE, INK, "middle")
        }
        spec.xLabel?.takeIf { it.isNotBlank() }?.let {
            c.text(clip(it, 50), (plotLeft + plotRight) / 2.0, plotBottom + 40.0, LABEL_SIZE, MUTED, "middle")
        }
        spec.yLabel?.takeIf { it.isNotBlank() }?.let {
            val centerY = top + plotHeight / 2.0
            c.text(clip(it, 30), 18.0, centerY + 4.0, LABEL_SIZE, MUTED, "middle", false, "rotate(-90 18 " + svgNum(centerY) + ")")
        }
        captionText(c, spec, height - 10.0)
        return c.build()
    }

    // ----------------------------------------------------------------- LINE

    private fun line(spec: FigureSpec): String {
        val categories = spec.categories.orEmpty().take(MAX_CATEGORIES).map { clip(it, 10) }
        val series = spec.series.orEmpty().take(MAX_SERIES).map { raw ->
            FigureSeries(
                name = raw.name,
                values = raw.values.take(maxOf(categories.size, raw.values.size))
                    .map { if (it.isFinite()) it else 0.0 },
            )
        }
        val legend = series.size > 1 || series.any { !it.name.isNullOrBlank() }
        val top = MARGIN + titleHeight(spec) + (if (legend) 28.0 else 0.0)
        val plotHeight = 230.0
        val plotBottom = top + plotHeight
        val plotLeft = MARGIN + 54.0
        val plotRight = WIDTH - MARGIN
        val plotWidth = plotRight - plotLeft
        val footer = if (spec.xLabel.isNullOrBlank()) 42.0 else 56.0
        val height = (plotBottom + footer + captionHeight(spec)).toInt()
        val (yMin, yMax, _) = yBounds(series.flatMap { it.values })

        val c = SvgCanvas(WIDTH, height, spec.title ?: spec.caption ?: "Line chart")
        heading(c, spec)
        if (legend) {
            var legendX = plotLeft
            series.forEachIndexed { index, entry ->
                c.rect(legendX, MARGIN + titleHeight(spec) + 2.0, 12.0, 12.0, SERIES[index % SERIES.size], null, 3.0)
                val label = clip(entry.name?.takeIf { it.isNotBlank() } ?: ("Series " + (index + 1)), 20)
                c.text(label, legendX + 18.0, MARGIN + titleHeight(spec) + 13.0, TICK_SIZE, INK, "start")
                legendX += 18.0 + label.length * 7.0 + 22.0
            }
        }
        for (tick in 0..4) {
            val y = plotBottom - plotHeight * tick / 4.0
            c.line(plotLeft, y, plotRight, y, if (tick == 0) AXIS else GRID, 1.0)
            c.text(svgNum(yMin + (yMax - yMin) * tick / 4.0), plotLeft - 10.0, y + 4.0, TICK_SIZE, MUTED, "end")
        }
        c.line(plotLeft, top, plotLeft, plotBottom, AXIS, 1.0)

        val count = maxOf(categories.size, series.maxOfOrNull { it.values.size } ?: 0, 1)
        val step = if (count <= 1) 0.0 else plotWidth / (count - 1)
        fun xAt(index: Int): Double = plotLeft + step * index
        fun yAt(value: Double): Double = plotBottom - (value - yMin) / (yMax - yMin) * plotHeight

        categories.forEachIndexed { index, category ->
            c.line(xAt(index), plotBottom, xAt(index), plotBottom + 5.0, AXIS, 1.0)
            c.text(category, xAt(index), plotBottom + 20.0, TICK_SIZE, INK, "middle")
        }
        series.forEachIndexed { seriesIndex, entry ->
            val color = SERIES[seriesIndex % SERIES.size]
            val points = entry.values.mapIndexed { index, value -> xAt(index) to yAt(value) }
            if (points.size > 1) c.polyline(points, color, 2.4)
            points.forEach { point -> c.circle(point.first, point.second, 3.6, WHITE, color, 2.2) }
        }
        spec.xLabel?.takeIf { it.isNotBlank() }?.let {
            c.text(clip(it, 50), (plotLeft + plotRight) / 2.0, plotBottom + 42.0, LABEL_SIZE, MUTED, "middle")
        }
        spec.yLabel?.takeIf { it.isNotBlank() }?.let {
            val centerY = top + plotHeight / 2.0
            c.text(clip(it, 30), 18.0, centerY + 4.0, LABEL_SIZE, MUTED, "middle", false, "rotate(-90 18 " + svgNum(centerY) + ")")
        }
        captionText(c, spec, height - 10.0)
        return c.build()
    }

    // ------------------------------------------------------------------ PIE

    private fun pie(spec: FigureSpec): String {
        val sorted = spec.slices.orEmpty()
            .map { FigureSlice(clip(it.label, 24), if (it.value.isFinite() && it.value > 0.0) it.value else 0.0) }
            .sortedByDescending { it.value }
        val slices = if (sorted.size > MAX_SLICES) {
            val head = sorted.take(MAX_SLICES - 1)
            val rest = sorted.drop(MAX_SLICES - 1).sumOf { it.value }
            head + FigureSlice("Other", rest)
        } else {
            sorted
        }
        val total = slices.sumOf { it.value }.takeIf { it > 0.0 } ?: 1.0
        val contentTop = MARGIN + titleHeight(spec)
        val radius = 112.0
        val centerX = 208.0
        val centerY = contentTop + radius
        val legendX = 348.0
        val legendRight = WIDTH - MARGIN
        val legendBottom = contentTop + slices.size * 26.0
        val height = (maxOf(centerY + radius, legendBottom) + 24.0 + captionHeight(spec)).toInt()
        val c = SvgCanvas(WIDTH, height, spec.title ?: spec.caption ?: "Pie chart")
        heading(c, spec)

        var angle = -90.0
        slices.forEachIndexed { index, slice ->
            val sweep = slice.value / total * 360.0
            val a1 = Math.toRadians(angle)
            val a2 = Math.toRadians(angle + sweep)
            val x1 = centerX + radius * Math.cos(a1)
            val y1 = centerY + radius * Math.sin(a1)
            val x2 = centerX + radius * Math.cos(a2)
            val y2 = centerY + radius * Math.sin(a2)
            val large = if (sweep > 180.0) 1 else 0
            val d = "M " + svgNum(centerX) + " " + svgNum(centerY) + " L " + svgNum(x1) + " " + svgNum(y1) +
                " A " + svgNum(radius) + " " + svgNum(radius) + " 0 " + large + " 1 " + svgNum(x2) + " " + svgNum(y2) + " Z"
            c.path(d, SERIES[index % SERIES.size], WHITE, 2.0)
            if (sweep >= 26.0) {
                val mid = Math.toRadians(angle + sweep / 2.0)
                c.text(
                    svgNum(slice.value / total * 100.0) + "%",
                    centerX + radius * 0.62 * Math.cos(mid),
                    centerY + radius * 0.62 * Math.sin(mid) + 4.0,
                    TICK_SIZE,
                    WHITE,
                    "middle",
                    true,
                )
            }
            angle += sweep
        }
        if (spec.donut) c.circle(centerX, centerY, radius * 0.55, WHITE)

        slices.forEachIndexed { index, slice ->
            val y = contentTop + index * 26.0
            c.rect(legendX, y, 13.0, 13.0, SERIES[index % SERIES.size], null, 3.0)
            c.text(slice.label.takeIf { it.isNotBlank() } ?: "(unlabelled)", legendX + 21.0, y + 11.0, LABEL_SIZE, INK, "start")
            c.text(svgNum(slice.value / total * 100.0) + "%", legendRight, y + 11.0, LABEL_SIZE, MUTED, "end")
        }
        captionText(c, spec, height - 10.0)
        return c.build()
    }

    // ---------------------------------------------------------- NUMBER_LINE

    private fun numberLine(spec: FigureSpec): String {
        val minValue = spec.min ?: 0.0
        val maxValue = if ((spec.max ?: 0.0) > minValue) (spec.max ?: minValue + 1.0) else minValue + 1.0
        val span = maxValue - minValue
        var step = spec.step?.takeIf { it > 0.0 && it.isFinite() } ?: niceStep(span / 10.0)
        if (span / step > MAX_TICKS) step = span / MAX_TICKS
        val contentTop = MARGIN + titleHeight(spec)
        val axisY = contentTop + 96.0
        val height = (axisY + 56.0 + captionHeight(spec)).toInt()
        val left = MARGIN + 26.0
        val right = WIDTH - MARGIN - 26.0

        val c = SvgCanvas(WIDTH, height, spec.title ?: "Number line")
        heading(c, spec)
        fun position(value: Double): Double = left + (value - minValue) / span * (right - left)

        c.line(left - 16.0, axisY, right + 16.0, axisY, INK, 1.8)
        c.polygon(listOf((right + 16.0) to axisY, (right + 7.0) to (axisY - 5.0), (right + 7.0) to (axisY + 5.0)), INK)
        c.polygon(listOf((left - 16.0) to axisY, (left - 7.0) to (axisY - 5.0), (left - 7.0) to (axisY + 5.0)), INK)

        val tickCount = Math.round(span / step).toInt().coerceIn(1, MAX_TICKS)
        val labelEvery = maxOf(1, Math.ceil(tickCount / 12.0).toInt())
        for (index in 0..tickCount) {
            val value = minValue + index * step
            val x = position(value)
            c.line(x, axisY - 6.0, x, axisY + 6.0, AXIS, 1.2)
            if (index % labelEvery == 0) {
                c.text(svgNum(value), x, axisY + 24.0, TICK_SIZE, INK, "middle")
            }
        }

        spec.intervals.orEmpty().take(MAX_INTERVALS).forEach { interval ->
            val x1 = position(minOf(interval.from, interval.to))
            val x2 = position(maxOf(interval.from, interval.to))
            c.rect(x1, axisY - 46.0, x2 - x1, 18.0, PRIMARY_SOFT, PRIMARY, 9.0)
            interval.label?.takeIf { it.isNotBlank() }?.let {
                c.text(clip(it, 34), (x1 + x2) / 2.0, axisY - 33.0, TICK_SIZE, PRIMARY, "middle")
            }
        }

        spec.marks.orEmpty().take(MAX_MARKS).forEach { mark ->
            val x = position(mark.value)
            if (mark.open) {
                c.circle(x, axisY, 5.5, WHITE, PRIMARY, 2.4)
            } else {
                c.circle(x, axisY, 5.5, PRIMARY)
            }
            mark.label?.takeIf { it.isNotBlank() }?.let {
                c.text(clip(it, 18), x, axisY - 14.0, TICK_SIZE, INK, "middle", true)
            }
        }
        captionText(c, spec, height - 10.0)
        return c.build()
    }

    // ------------------------------------------------------------- GEOMETRY

    private fun geometry(spec: FigureSpec): String {
        val viewBox = spec.viewBox ?: FigureViewBox()
        val boxWidth = viewBox.width.takeIf { it > 0.0 && it.isFinite() } ?: 10.0
        val boxHeight = viewBox.height.takeIf { it > 0.0 && it.isFinite() } ?: 10.0
        val top = MARGIN + titleHeight(spec)
        val availableWidth = WIDTH - 2.0 * MARGIN
        val availableHeight = 340.0
        val scale = minOf(availableWidth / boxWidth, availableHeight / boxHeight)
        val drawWidth = boxWidth * scale
        val drawHeight = boxHeight * scale
        val originX = MARGIN + (availableWidth - drawWidth) / 2.0
        val originY = top + (availableHeight - drawHeight) / 2.0
        val height = (top + availableHeight + 10.0 + captionHeight(spec)).toInt()

        val c = SvgCanvas(WIDTH, height, spec.title ?: "Geometry figure")
        heading(c, spec)
        val sx: (Double) -> Double = { x -> originX + (x - viewBox.minX) * scale }
        val sy: (Double) -> Double = { y -> originY + drawHeight - (y - viewBox.minY) * scale }

        if (spec.grid && boxWidth <= 40.0 && boxHeight <= 40.0) {
            var gx = Math.ceil(viewBox.minX)
            while (gx <= viewBox.minX + boxWidth) {
                c.line(sx(gx), originY, sx(gx), originY + drawHeight, GRID, 1.0)
                gx += 1.0
            }
            var gy = Math.ceil(viewBox.minY)
            while (gy <= viewBox.minY + boxHeight) {
                c.line(originX, sy(gy), originX + drawWidth, sy(gy), GRID, 1.0)
                gy += 1.0
            }
        }

        spec.elements.orEmpty().take(MAX_ELEMENTS).forEach { element -> drawElement(c, element, sx, sy, scale) }
        captionText(c, spec, height - 10.0)
        return c.build()
    }

    private fun drawElement(
        c: SvgCanvas,
        element: FigureElement,
        sx: (Double) -> Double,
        sy: (Double) -> Double,
        scale: Double,
    ) {
        val dash = if (element.style?.equals("DASHED", ignoreCase = true) == true) "7 5" else null
        when (element.type) {
            "SEGMENT" -> {
                val from = element.from ?: return
                val to = element.to ?: return
                c.line(sx(from[0]), sy(from[1]), sx(to[0]), sy(to[1]), INK, 1.8, dash)
                element.label?.takeIf { it.isNotBlank() }?.let {
                    labelAt(c, sx, sy, (from[0] + to[0]) / 2.0, (from[1] + to[1]) / 2.0, it)
                }
            }
            "POLYGON" -> {
                val points = element.points ?: return
                val screen = points.map { sx(it[0]) to sy(it[1]) }
                c.polygon(screen, if (element.filled) PRIMARY_SOFT else "none", PRIMARY, 1.8)
                element.label?.takeIf { it.isNotBlank() }?.let {
                    labelAt(c, sx, sy, points.map { it[0] }.average(), points.map { it[1] }.average(), it)
                }
            }
            "CIRCLE" -> {
                val center = element.center ?: return
                val radius = element.radius ?: return
                c.circle(sx(center[0]), sy(center[1]), radius * scale, "none", PRIMARY, 1.8)
                element.label?.takeIf { it.isNotBlank() }?.let { labelAt(c, sx, sy, center[0], center[1], it) }
            }
            "ARC" -> {
                val center = element.center ?: return
                val radius = element.radius ?: return
                val start = element.startAngle ?: return
                val end = element.endAngle ?: return
                c.path(arcPath(sx(center[0]), sy(center[1]), radius * scale, start, end), "none", PRIMARY, 1.8, dash)
            }
            "POINT" -> {
                val at = element.at ?: return
                c.circle(sx(at[0]), sy(at[1]), 3.6, PRIMARY)
                element.label?.takeIf { it.isNotBlank() }?.let { labelAt(c, sx, sy, at[0], at[1], it) }
            }
            "ANGLE" -> {
                val vertex = element.vertex ?: return
                val from = element.from ?: return
                val to = element.to ?: return
                angleArc(c, sx, sy, vertex, from, to, element.label)
            }
            "RIGHT_ANGLE" -> {
                val vertex = element.vertex ?: return
                val from = element.from ?: return
                val to = element.to ?: return
                rightAngle(c, sx, sy, vertex, from, to)
            }
            "RECT" -> {
                val from = element.from ?: return
                val to = element.to ?: return
                val x1 = sx(from[0])
                val y1 = sy(from[1])
                val x2 = sx(to[0])
                val y2 = sy(to[1])
                c.rect(
                    minOf(x1, x2),
                    minOf(y1, y2),
                    Math.abs(x2 - x1),
                    Math.abs(y2 - y1),
                    if (element.filled) PRIMARY_SOFT else "none",
                    PRIMARY,
                    0.0,
                    1.8,
                )
                element.label?.takeIf { it.isNotBlank() }?.let {
                    labelAt(c, sx, sy, (from[0] + to[0]) / 2.0, (from[1] + to[1]) / 2.0, it)
                }
            }
            "ELLIPSE" -> {
                val center = element.center ?: return
                val rx = element.radius ?: return
                val ry = element.radiusY ?: return
                c.ellipse(sx(center[0]), sy(center[1]), rx * scale, ry * scale, "none", PRIMARY, 1.8)
                element.label?.takeIf { it.isNotBlank() }?.let { labelAt(c, sx, sy, center[0], center[1], it) }
            }
            "ARROW" -> {
                val from = element.from ?: return
                val to = element.to ?: return
                val x1 = sx(from[0])
                val y1 = sy(from[1])
                val x2 = sx(to[0])
                val y2 = sy(to[1])
                c.line(x1, y1, x2, y2, INK, 1.8, dash)
                arrowHead(c, x1, y1, x2, y2, INK)
                element.label?.takeIf { it.isNotBlank() }?.let {
                    labelAt(c, sx, sy, (from[0] + to[0]) / 2.0, (from[1] + to[1]) / 2.0, it)
                }
            }
            "BEZIER" -> {
                val from = element.from ?: return
                val to = element.to ?: return
                val control1 = element.control1 ?: return
                val control2 = element.control2 ?: return
                val d = "M " + svgNum(sx(from[0])) + " " + svgNum(sy(from[1])) +
                    " C " + svgNum(sx(control1[0])) + " " + svgNum(sy(control1[1])) +
                    " " + svgNum(sx(control2[0])) + " " + svgNum(sy(control2[1])) +
                    " " + svgNum(sx(to[0])) + " " + svgNum(sy(to[1]))
                c.path(d, "none", PRIMARY, 1.8, dash)
            }
            "LABEL" -> {
                val at = element.at ?: return
                val text = element.text ?: return
                c.text(clip(text, 40), sx(at[0]), sy(at[1]), LABEL_SIZE, INK, "middle")
            }
        }
    }

    private fun labelAt(
        c: SvgCanvas,
        sx: (Double) -> Double,
        sy: (Double) -> Double,
        x: Double,
        y: Double,
        text: String,
    ) {
        c.text(clip(text, 22), sx(x) + 9.0, sy(y) - 9.0, LABEL_SIZE, INK, "start", true)
    }

    private fun arcPath(centerX: Double, centerY: Double, radius: Double, startDeg: Double, endDeg: Double): String {
        val a1 = Math.toRadians(-startDeg)
        val a2 = Math.toRadians(-endDeg)
        val x1 = centerX + radius * Math.cos(a1)
        val y1 = centerY + radius * Math.sin(a1)
        val x2 = centerX + radius * Math.cos(a2)
        val y2 = centerY + radius * Math.sin(a2)
        var delta = endDeg - startDeg
        while (delta <= -360.0) delta += 360.0
        while (delta > 360.0) delta -= 360.0
        val large = if (Math.abs(delta) > 180.0) 1 else 0
        val sweep = if (delta >= 0.0) 1 else 0
        return "M " + svgNum(x1) + " " + svgNum(y1) + " A " + svgNum(radius) + " " + svgNum(radius) + " 0 " +
            large + " " + sweep + " " + svgNum(x2) + " " + svgNum(y2)
    }

    private fun angleArc(
        c: SvgCanvas,
        sx: (Double) -> Double,
        sy: (Double) -> Double,
        vertex: List<Double>,
        from: List<Double>,
        to: List<Double>,
        label: String?,
    ) {
        val vx = sx(vertex[0])
        val vy = sy(vertex[1])
        val a1 = Math.atan2(sy(from[1]) - vy, sx(from[0]) - vx)
        val a2 = Math.atan2(sy(to[1]) - vy, sx(to[0]) - vx)
        var delta = a2 - a1
        while (delta <= -Math.PI) delta += 2.0 * Math.PI
        while (delta > Math.PI) delta -= 2.0 * Math.PI
        val radius = 26.0
        val x1 = vx + radius * Math.cos(a1)
        val y1 = vy + radius * Math.sin(a1)
        val x2 = vx + radius * Math.cos(a2)
        val y2 = vy + radius * Math.sin(a2)
        val large = if (Math.abs(delta) > Math.PI) 1 else 0
        val sweep = if (delta >= 0.0) 1 else 0
        c.path(
            "M " + svgNum(x1) + " " + svgNum(y1) + " A " + svgNum(radius) + " " + svgNum(radius) + " 0 " +
                large + " " + sweep + " " + svgNum(x2) + " " + svgNum(y2),
            "none",
            ACCENT,
            1.6,
        )
        val mid = a1 + delta / 2.0
        c.text(
            clip(label ?: "", 20),
            vx + (radius + 16.0) * Math.cos(mid),
            vy + (radius + 16.0) * Math.sin(mid) + 4.0,
            TICK_SIZE,
            ACCENT,
            "middle",
            true,
        )
    }

    private fun rightAngle(
        c: SvgCanvas,
        sx: (Double) -> Double,
        sy: (Double) -> Double,
        vertex: List<Double>,
        from: List<Double>,
        to: List<Double>,
    ) {
        val vx = sx(vertex[0])
        val vy = sy(vertex[1])
        val d1x = sx(from[0]) - vx
        val d1y = sy(from[1]) - vy
        val d2x = sx(to[0]) - vx
        val d2y = sy(to[1]) - vy
        val n1 = Math.hypot(d1x, d1y).takeIf { it > 0.0 } ?: 1.0
        val n2 = Math.hypot(d2x, d2y).takeIf { it > 0.0 } ?: 1.0
        val size = 12.0
        val p1 = (vx + d1x / n1 * size) to (vy + d1y / n1 * size)
        val p3 = (vx + d2x / n2 * size) to (vy + d2y / n2 * size)
        val p2 = (p1.first + d2x / n2 * size) to (p1.second + d2y / n2 * size)
        c.polyline(listOf(p1, p2, p3), ACCENT, 1.4)
    }

    // ---------------------------------------------------------------- TABLE

    private fun table(spec: FigureSpec): String {
        val headers = spec.headers.orEmpty().take(MAX_COLUMNS).map { clip(it, 20) }
        val rows = spec.rows.orEmpty().take(MAX_ROWS).map { row ->
            row.take(maxOf(headers.size, 1)).map { clip(it, 20) }
        }
        val columns = maxOf(headers.size, 1)
        val top = MARGIN + titleHeight(spec)
        val rowHeight = 28.0
        val height = (top + rowHeight * (rows.size + 1) + 12.0 + captionHeight(spec)).toInt()
        val tableWidth = WIDTH - 2.0 * MARGIN
        val columnWidth = tableWidth / columns

        val c = SvgCanvas(WIDTH, height, spec.title ?: "Table")
        heading(c, spec)
        c.rect(MARGIN, top, tableWidth, rowHeight, PRIMARY_SOFT, GRID)
        headers.forEachIndexed { index, header ->
            c.text(header, MARGIN + columnWidth * index + 10.0, top + 18.0, LABEL_SIZE, INK, "start", true)
        }
        rows.forEachIndexed { rowIndex, row ->
            val y = top + rowHeight * (rowIndex + 1)
            c.rect(MARGIN, y, tableWidth, rowHeight, if (rowIndex % 2 == 1) SURFACE else WHITE, GRID)
            row.forEachIndexed { columnIndex, cell ->
                c.text(cell, MARGIN + columnWidth * columnIndex + 10.0, y + 18.0, LABEL_SIZE, INK, "start")
            }
        }
        captionText(c, spec, top + rowHeight * (rows.size + 1) + 12.0)
        return c.build()
    }

    // ----------------------------------------------------------------- FLOW

    private fun flow(spec: FigureSpec): String {
        val steps = spec.steps.orEmpty().take(MAX_STEPS).map { clip(it, 60) }
        val perRow = 4
        val rows = maxOf((steps.size + perRow - 1) / perRow, 1)
        val top = MARGIN + titleHeight(spec)
        val boxHeight = 58.0
        val rowGap = 36.0
        val rail = if (spec.cyclic && steps.size > 1) 36.0 else 0.0
        val height = (
            top + rows * boxHeight + (rows - 1) * rowGap + (if (rail > 0.0) rail + 8.0 else 10.0) + captionHeight(spec)
            ).toInt()
        val usable = WIDTH - 2.0 * MARGIN

        val c = SvgCanvas(WIDTH, height, spec.title ?: "Flow chart")
        heading(c, spec)
        steps.forEachIndexed { index, step ->
            val row = index / perRow
            val column = index % perRow
            val inRow = minOf(perRow, steps.size - row * perRow)
            val slot = usable / maxOf(inRow, 1)
            val boxWidth = slot - 24.0
            val x = MARGIN + column * slot + 12.0
            val y = top + row * (boxHeight + rowGap)
            c.rect(x, y, boxWidth, boxHeight, WHITE, PRIMARY, 8.0, 1.5)
            wrappedText(c, step, x + boxWidth / 2.0, y + 24.0, boxWidth - 16.0, 12.0)
            val hasNext = index < steps.size - 1
            if (hasNext && column < inRow - 1) {
                arrowRight(c, x + boxWidth, y + boxHeight / 2.0, x + slot - 12.0)
            } else if (hasNext) {
                arrowDown(c, x + boxWidth / 2.0, y + boxHeight, boxHeight + rowGap - 10.0)
            }
        }
        if (spec.cyclic && steps.size > 1) {
            val lastIndex = steps.size - 1
            val lastRow = lastIndex / perRow
            val lastColumn = lastIndex % perRow
            val lastInRow = minOf(perRow, steps.size - lastRow * perRow)
            val lastSlot = usable / maxOf(lastInRow, 1)
            val lastX = MARGIN + lastColumn * lastSlot + 12.0 + (lastSlot - 24.0) / 2.0
            val lastY = top + lastRow * (boxHeight + rowGap) + boxHeight
            val railY = lastY + 20.0
            val firstSlot = usable / maxOf(minOf(perRow, steps.size), 1)
            val firstX = MARGIN + 12.0 + (firstSlot - 24.0) / 2.0
            c.line(lastX, lastY, lastX, railY, MUTED, 1.4)
            c.line(lastX, railY, firstX, railY, MUTED, 1.4)
            arrowUp(c, firstX, railY, top - 6.0, MUTED)
        }
        captionText(c, spec, height - 10.0)
        return c.build()
    }

    private fun wrappedText(c: SvgCanvas, value: String, centerX: Double, firstY: Double, maxWidth: Double, size: Double) {
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
            c.text(lineText, centerX, firstY + index * (size + 2.0), size, INK, "middle")
        }
    }

    private fun arrowRight(c: SvgCanvas, x1: Double, y: Double, x2: Double) {
        c.line(x1, y, x2, y, PRIMARY, 1.5)
        c.polygon(listOf((x2 - 8.0) to (y - 4.5), x2 to y, (x2 - 8.0) to (y + 4.5)), PRIMARY)
    }

    private fun arrowDown(c: SvgCanvas, x: Double, y1: Double, length: Double) {
        val y2 = y1 + length
        c.line(x, y1, x, y2, PRIMARY, 1.5)
        c.polygon(listOf((x - 4.5) to (y2 - 8.0), x to y2, (x + 4.5) to (y2 - 8.0)), PRIMARY)
    }

    private fun arrowUp(c: SvgCanvas, x: Double, y1: Double, y2: Double, color: String) {
        c.line(x, y1, x, y2, color, 1.4)
        c.polygon(listOf((x - 4.5) to (y2 + 8.0), x to y2, (x + 4.5) to (y2 + 8.0)), color)
    }

    // ----------------------------------------------------------------- TREE

    private class TreeNode(val id: String, val label: String) {
        val children = mutableListOf<TreeNode>()
        var depth = 0
        var slot = 0.0
    }

    private fun tree(spec: FigureSpec): String {
        val raw = spec.nodes.orEmpty().take(MAX_TREE_NODES)
        val byId = LinkedHashMap<String, TreeNode>()
        raw.forEach { node ->
            if (node.id.isNotBlank()) byId.putIfAbsent(node.id, TreeNode(node.id, clip(node.label, 26)))
        }
        raw.forEach { node ->
            val child = byId[node.id] ?: return@forEach
            node.parent?.let { byId[it] }?.let { parent -> parent.children += child }
        }
        // A root is a node with no parent, or one whose parent is not a node.
        val roots = raw.mapNotNull { byId[it.id] }
            .filter { node -> raw.firstOrNull { it.id == node.id }?.parent?.let { byId.containsKey(it) } != true }
        val forest = roots.ifEmpty { byId.values.take(1) }

        var leaf = 0
        var maxDepth = 0
        fun assign(node: TreeNode, depth: Int) {
            node.depth = depth
            if (depth > maxDepth) maxDepth = depth
            if (node.children.isEmpty() || depth >= MAX_TREE_DEPTH) {
                node.slot = leaf.toDouble()
                leaf += 1
            } else {
                node.children.forEach { assign(it, depth + 1) }
                node.slot = node.children.map { it.slot }.average()
            }
        }
        forest.forEach { assign(it, 0) }

        val leaves = maxOf(leaf, 1)
        val slotWidth = 150.0
        val nodeWidth = 132.0
        val nodeHeight = 44.0
        val levelGap = 86.0
        val top = MARGIN + titleHeight(spec)
        val canvasWidth = maxOf(WIDTH, (MARGIN * 2.0 + nodeWidth + (leaves - 1) * slotWidth).toInt())
        val height = (top + (maxDepth + 1) * levelGap + 12.0 + captionHeight(spec)).toInt()

        val c = SvgCanvas(canvasWidth, height, spec.title ?: "Tree diagram")
        heading(c, spec, canvasWidth / 2.0)

        val all = mutableListOf<TreeNode>()
        fun collect(node: TreeNode, depth: Int) {
            if (depth > MAX_TREE_DEPTH) return
            all += node
            node.children.forEach { collect(it, depth + 1) }
        }
        forest.forEach { collect(it, 0) }

        fun xOf(node: TreeNode): Double = MARGIN + nodeWidth / 2.0 + node.slot * slotWidth
        fun yOf(node: TreeNode): Double = top + node.depth * levelGap

        all.forEach { node ->
            node.children.forEach { child ->
                val px = xOf(node)
                val py = yOf(node) + nodeHeight
                val childX = xOf(child)
                val childY = yOf(child)
                val midY = (py + childY) / 2.0
                c.polyline(listOf(px to py, px to midY, childX to midY, childX to childY), AXIS, 1.4)
            }
        }
        all.forEach { node ->
            val x = xOf(node)
            val y = yOf(node)
            c.rect(
                x - nodeWidth / 2.0,
                y,
                nodeWidth,
                nodeHeight,
                if (node.depth == 0) PRIMARY_SOFT else WHITE,
                PRIMARY,
                8.0,
                1.4,
            )
            wrappedText(c, node.label, x, y + 19.0, nodeWidth - 14.0, 11.5)
        }
        captionText(c, spec, height - 10.0, canvasWidth / 2.0)
        return c.build()
    }

    // ----------------------------------------------------------------- VENN

    private fun venn(spec: FigureSpec): String {
        val sets = spec.sets.orEmpty().take(3)
        val shared = spec.shared.orEmpty().take(MAX_SET_ITEMS)
        val top = MARGIN + titleHeight(spec)
        val radius = 118.0
        val centers: List<Pair<Double, Double>> = if (sets.size <= 2) {
            listOf(
                (WIDTH / 2.0 - 84.0) to (top + radius + 30.0),
                (WIDTH / 2.0 + 84.0) to (top + radius + 30.0),
            )
        } else {
            listOf(
                (WIDTH / 2.0) to (top + 128.0),
                (WIDTH / 2.0 - 90.0) to (top + 252.0),
                (WIDTH / 2.0 + 90.0) to (top + 252.0),
            )
        }
        val height = (centers.maxOf { it.second } + radius + 46.0 + captionHeight(spec)).toInt()
        val c = SvgCanvas(WIDTH, height, spec.title ?: spec.caption ?: "Venn diagram")
        heading(c, spec)
        val colors = listOf("#3B82F6", "#EF4444", "#10B981")
        centers.forEachIndexed { index, center ->
            c.circle(center.first, center.second, radius, colors[index % colors.size], null, 1.0, 0.14)
        }

        if (sets.size <= 2) {
            sets.forEachIndexed { index, set ->
                c.text(
                    clip(set.label ?: ("Set " + (index + 1)), 20),
                    centers[index].first,
                    centers[index].second - radius - 10.0,
                    LABEL_SIZE,
                    colors[index % colors.size],
                    "middle",
                    true,
                )
            }
            drawRegionItems(c, sets.getOrNull(0)?.items.orEmpty(), centers[0].first - radius * 0.42, centers[0].second)
            drawRegionItems(c, sets.getOrNull(1)?.items.orEmpty(), centers[1].first + radius * 0.42, centers[1].second)
            drawRegionItems(c, shared, WIDTH / 2.0, centers[0].second)
        } else {
            c.text(clip(sets[0].label ?: "Set 1", 20), centers[0].first, centers[0].second - radius - 10.0, LABEL_SIZE, colors[0], "middle", true)
            c.text(clip(sets[1].label ?: "Set 2", 20), centers[1].first - radius * 0.62, centers[1].second + radius + 18.0, LABEL_SIZE, colors[1], "middle", true)
            c.text(clip(sets[2].label ?: "Set 3", 20), centers[2].first + radius * 0.62, centers[2].second + radius + 18.0, LABEL_SIZE, colors[2], "middle", true)
            drawRegionItems(c, sets[0].items, centers[0].first, centers[0].second - 46.0)
            drawRegionItems(c, sets[1].items, centers[1].first - 46.0, centers[1].second + 30.0)
            drawRegionItems(c, sets[2].items, centers[2].first + 46.0, centers[2].second + 30.0)
            drawRegionItems(c, shared, WIDTH / 2.0, top + 176.0)
        }
        captionText(c, spec, height - 10.0)
        return c.build()
    }

    private fun drawRegionItems(c: SvgCanvas, items: List<String>, centerX: Double, centerY: Double) {
        val visible = items.take(MAX_SET_ITEMS)
        val startY = centerY - (visible.size - 1) * 7.0 + 4.0
        visible.forEachIndexed { index, item ->
            c.text(clip(item, 18), centerX, startY + index * 14.0, 10.5, INK, "middle")
        }
    }

    private fun arrowHead(c: SvgCanvas, x1: Double, y1: Double, x2: Double, y2: Double, color: String) {
        val angle = Math.atan2(y2 - y1, x2 - x1)
        val size = 11.0
        val a = angle + Math.toRadians(155.0)
        val b = angle - Math.toRadians(155.0)
        c.polygon(
            listOf(
                x2 to y2,
                (x2 + size * Math.cos(a)) to (y2 + size * Math.sin(a)),
                (x2 + size * Math.cos(b)) to (y2 + size * Math.sin(b)),
            ),
            color,
        )
    }

    // -------------------------------------------------------------- helpers

    private fun heading(c: SvgCanvas, spec: FigureSpec, centerX: Double = WIDTH / 2.0) {
        spec.title?.takeIf { it.isNotBlank() }?.let {
            c.text(clip(it, 80), centerX, 24.0, TITLE_SIZE, INK, "middle", true)
        }
    }

    private fun captionText(c: SvgCanvas, spec: FigureSpec, y: Double, centerX: Double = WIDTH / 2.0) {
        spec.caption?.takeIf { it.isNotBlank() }?.let {
            c.text(clip(it, 110), centerX, y, CAPTION_SIZE, MUTED, "middle")
        }
    }

    private fun titleHeight(spec: FigureSpec): Double = if (spec.title.isNullOrBlank()) 0.0 else 32.0

    private fun captionHeight(spec: FigureSpec): Double = if (spec.caption.isNullOrBlank()) 0.0 else 28.0

    /** A "nice" axis step (1, 2 or 5 times a power of ten) around [raw]. */
    private fun niceStep(raw: Double): Double {
        if (!raw.isFinite() || raw <= 0.0) return 1.0
        val magnitude = Math.pow(10.0, Math.floor(Math.log10(raw)))
        val normalized = raw / magnitude
        val step = when {
            normalized <= 1.0 -> 1.0
            normalized <= 2.0 -> 2.0
            normalized <= 5.0 -> 5.0
            else -> 10.0
        }
        return step * magnitude
    }

    /** A y axis that always includes zero and lands on nice tick values. */
    private fun yBounds(values: List<Double>): Triple<Double, Double, Double> {
        val rawMin = minOf(0.0, values.minOrNull() ?: 0.0)
        val rawMax = maxOf(0.0, values.maxOrNull() ?: 0.0)
        if (rawMin == 0.0 && rawMax == 0.0) return Triple(0.0, 1.0, 1.0)
        val step = niceStep(maxOf(rawMax - rawMin, 1.0E-9) / 4.0)
        val min = Math.floor(rawMin / step) * step
        val max = Math.ceil(rawMax / step) * step
        return Triple(min, if (max > min) max else min + step, step)
    }

    private fun clip(value: String, max: Int): String =
        if (value.length <= max) value else value.substring(0, maxOf(max - 3, 1)) + "..."
}
