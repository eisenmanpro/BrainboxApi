package com.afrithecus.brainbox.api.content.figure

import java.util.Locale

/** Compact, locale-stable number formatting for SVG attributes and labels. */
internal fun svgNum(value: Double): String {
    if (value.isNaN() || value.isInfinite()) return "0"
    val rounded = Math.round(value * 100.0) / 100.0
    if (rounded == Math.floor(rounded) && Math.abs(rounded) < 1.0E15) {
        return rounded.toLong().toString()
    }
    return String.format(Locale.US, "%.2f", rounded).trimEnd('0').trimEnd('.')
}

/** XML text escaping; the single point where model text becomes markup. */
internal fun svgEsc(value: String): String = value
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")
    .replace("'", "&apos;")

/**
 * A tiny SVG writer. Every attribute is constructed here, so a caller can compose
 * only the primitives it offers; there is no path to raw markup. All text passes
 * through [svgEsc] and all numbers through [svgNum].
 */
internal class SvgCanvas(private val width: Int, private val height: Int, private val documentTitle: String) {

    private val body = StringBuilder()

    fun text(
        content: String,
        x: Double,
        y: Double,
        size: Double,
        color: String,
        anchor: String = "start",
        bold: Boolean = false,
        transform: String? = null,
    ): SvgCanvas {
        body.append("<text x=\"").append(svgNum(x)).append("\" y=\"").append(svgNum(y))
        body.append("\" font-size=\"").append(svgNum(size)).append("\" fill=\"").append(color)
        body.append("\" text-anchor=\"").append(anchor).append("\" font-family=\"Helvetica,Arial,sans-serif\"")
        if (bold) body.append(" font-weight=\"bold\"")
        if (transform != null) body.append(" transform=\"").append(transform).append("\"")
        body.append(">").append(svgEsc(content)).append("</text>")
        return this
    }

    fun line(
        x1: Double,
        y1: Double,
        x2: Double,
        y2: Double,
        color: String,
        strokeWidth: Double = 1.0,
        dash: String? = null,
    ): SvgCanvas {
        body.append("<line x1=\"").append(svgNum(x1)).append("\" y1=\"").append(svgNum(y1))
        body.append("\" x2=\"").append(svgNum(x2)).append("\" y2=\"").append(svgNum(y2))
        body.append("\" stroke=\"").append(color).append("\" stroke-width=\"").append(svgNum(strokeWidth)).append("\"")
        if (dash != null) body.append(" stroke-dasharray=\"").append(dash).append("\"")
        body.append("/>")
        return this
    }

    fun rect(
        x: Double,
        y: Double,
        w: Double,
        h: Double,
        fill: String,
        stroke: String? = null,
        rx: Double = 0.0,
        strokeWidth: Double = 1.0,
        opacity: Double? = null,
    ): SvgCanvas {
        body.append("<rect x=\"").append(svgNum(x)).append("\" y=\"").append(svgNum(y))
        body.append("\" width=\"").append(svgNum(maxOf(w, 0.1))).append("\" height=\"").append(svgNum(maxOf(h, 0.1))).append("\"")
        if (rx > 0.0) body.append(" rx=\"").append(svgNum(rx)).append("\"")
        body.append(" fill=\"").append(fill).append("\"")
        if (stroke != null) body.append(" stroke=\"").append(stroke).append("\" stroke-width=\"").append(svgNum(strokeWidth)).append("\"")
        if (opacity != null) body.append(" fill-opacity=\"").append(svgNum(opacity)).append("\"")
        body.append("/>")
        return this
    }

    fun circle(
        cx: Double,
        cy: Double,
        r: Double,
        fill: String,
        stroke: String? = null,
        strokeWidth: Double = 1.0,
    ): SvgCanvas {
        body.append("<circle cx=\"").append(svgNum(cx)).append("\" cy=\"").append(svgNum(cy))
        body.append("\" r=\"").append(svgNum(maxOf(r, 0.1))).append("\" fill=\"").append(fill).append("\"")
        if (stroke != null) body.append(" stroke=\"").append(stroke).append("\" stroke-width=\"").append(svgNum(strokeWidth)).append("\"")
        body.append("/>")
        return this
    }

    fun path(
        d: String,
        fill: String = "none",
        stroke: String? = null,
        strokeWidth: Double = 1.0,
        dash: String? = null,
    ): SvgCanvas {
        body.append("<path d=\"").append(d).append("\" fill=\"").append(fill).append("\"")
        if (stroke != null) {
            body.append(" stroke=\"").append(stroke).append("\" stroke-width=\"").append(svgNum(strokeWidth))
            body.append("\" stroke-linejoin=\"round\" stroke-linecap=\"round\"")
        }
        if (dash != null) body.append(" stroke-dasharray=\"").append(dash).append("\"")
        body.append("/>")
        return this
    }

    fun polyline(
        points: List<Pair<Double, Double>>,
        stroke: String,
        strokeWidth: Double = 1.0,
        fill: String = "none",
        dash: String? = null,
    ): SvgCanvas {
        body.append("<polyline points=\"").append(svgPoints(points)).append("\" fill=\"").append(fill)
        body.append("\" stroke=\"").append(stroke).append("\" stroke-width=\"").append(svgNum(strokeWidth))
        body.append("\" stroke-linejoin=\"round\" stroke-linecap=\"round\"")
        if (dash != null) body.append(" stroke-dasharray=\"").append(dash).append("\"")
        body.append("/>")
        return this
    }

    fun polygon(
        points: List<Pair<Double, Double>>,
        fill: String,
        stroke: String? = null,
        strokeWidth: Double = 1.0,
    ): SvgCanvas {
        body.append("<polygon points=\"").append(svgPoints(points)).append("\" fill=\"").append(fill).append("\"")
        if (stroke != null) {
            body.append(" stroke=\"").append(stroke).append("\" stroke-width=\"").append(svgNum(strokeWidth))
            body.append("\" stroke-linejoin=\"round\"")
        }
        body.append("/>")
        return this
    }

    fun build(): String = "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 " + width + " " + height +
        "\" width=\"" + width + "\" height=\"" + height + "\" role=\"img\"><title>" + svgEsc(documentTitle) +
        "</title>" + body + "</svg>"

    private fun svgPoints(points: List<Pair<Double, Double>>): String {
        val out = StringBuilder()
        points.forEachIndexed { index, point ->
            if (index > 0) out.append(' ')
            out.append(svgNum(point.first)).append(',').append(svgNum(point.second))
        }
        return out.toString()
    }
}
