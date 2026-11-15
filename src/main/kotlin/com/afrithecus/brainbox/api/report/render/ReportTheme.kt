package com.afrithecus.brainbox.api.report.render

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.font.PDFont
import org.apache.pdfbox.pdmodel.font.PDType0Font
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import java.awt.Color
import java.io.InputStream
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Design tokens and embedded fonts for the report renderers (plan A1, decision 5).
 *
 * Liberation Sans (body) and Liberation Serif (display) are bundled with the API under
 * `src/main/resources/report/fonts` together with their SIL OFL licence, so a generated
 * PDF looks the same on a developer machine and on a stripped server. Body text uses
 * Liberation Sans because embedded Lato loses the "ti" pair on text extraction (A14/F20).
 * If a bundled font is missing the loader falls back to the PDF standard 14 fonts
 * instead of failing the report.
 */
class ReportTheme(private val document: PDDocument) {

    // A14/F20: Lato's PDFBox ToUnicode table loses the "ti" pair ("Otieno" -> "O eno"), so
    // body text uses Liberation Sans (decision 5's documented extraction fallback). Display
    // text stays Liberation Serif, which extracts cleanly.
    val sans: PDFont = load("LiberationSans-Regular.ttf", Standard14Fonts.FontName.HELVETICA)
    val sansLight: PDFont = load("LiberationSans-Regular.ttf", Standard14Fonts.FontName.HELVETICA)
    val sansBold: PDFont = load("LiberationSans-Bold.ttf", Standard14Fonts.FontName.HELVETICA_BOLD)
    val sansBlack: PDFont = load("LiberationSans-Bold.ttf", Standard14Fonts.FontName.HELVETICA_BOLD)
    val sansItalic: PDFont = load("LiberationSans-Italic.ttf", Standard14Fonts.FontName.HELVETICA_OBLIQUE)
    val serif: PDFont = load("LiberationSerif-Regular.ttf", Standard14Fonts.FontName.TIMES_ROMAN)
    val serifBold: PDFont = load("LiberationSerif-Bold.ttf", Standard14Fonts.FontName.TIMES_BOLD)

    private fun load(file: String, fallback: Standard14Fonts.FontName): PDFont {
        val resource = ReportTheme::class.java.getResourceAsStream("/report/fonts/$file")
            ?: return PDType1Font(fallback)
        return resource.use { input: InputStream ->
            runCatching { PDType0Font.load(document, input, true) }
                .getOrElse { PDType1Font(fallback) }
        }
    }

    /** Font width in points, tolerant of glyphs the font cannot measure. */
    fun textWidth(font: PDFont, value: String?, size: Float): Float {
        if (value.isNullOrEmpty()) return 0f
        return runCatching { font.getStringWidth(sanitize(value)) / 1000f * size }.getOrDefault(0f)
    }

    fun trackedWidth(value: String?, font: PDFont, size: Float, spacing: Float): Float {
        if (value.isNullOrEmpty()) return 0f
        var total = 0f
        for (ch in value) total += textWidth(font, ch.toString(), size) + spacing
        return (total - spacing).coerceAtLeast(0f)
    }

    companion object {
        // v1 palette: report semantics (navy/teal/gold + level tints)
        val NAVY = Color(0x16, 0x29, 0x4A)
        val NAVY_SOFT = Color(0x2A, 0x46, 0x6F)
        val TEAL = Color(0x0F, 0x7A, 0x6B)
        val TEAL_DARK = Color(0x0A, 0x5B, 0x50)
        val MINT = Color(0xE9, 0xF4, 0xEF)
        val MINT_SOFT = Color(0xF4, 0xFA, 0xF7)
        val GOLD = Color(0xC4, 0x9A, 0x2A)
        val GOLD_SOFT = Color(0xFB, 0xF5, 0xE4)
        val INK = Color(0x23, 0x32, 0x40)
        val MUTED = Color(0x5D, 0x6B, 0x7C)
        val LINE = Color(0xD7, 0xDF, 0xE8)
        val PAPER = Color(0xFF, 0xFF, 0xFF)
        val RED = Color(0xB2, 0x3A, 0x34)
        val RED_SOFT = Color(0xFB, 0xED, 0xEB)
        val AMBER = Color(0xB7, 0x77, 0x1E)
        val AMBER_SOFT = Color(0xFB, 0xF3, 0xE2)
        val BLUE_SOFT = Color(0xEA, 0xF0, 0xF8)
        val BLUE_INK = Color(0x27, 0x4B, 0x7A)

        // v2 palette: modern report-book chrome (band, accents)
        val DARK = Color(0x0B, 0x1B, 0x33)
        val DARK_2 = Color(0x12, 0x30, 0x5A)
        val TEAL2 = Color(0x12, 0xA9, 0x8E)
        val TEAL2_DEEP = Color(0x0A, 0x7A, 0x67)
        val TEAL2_SOFT = Color(0xE4, 0xF5, 0xF0)
        val GOLD2 = Color(0xD9, 0xA6, 0x3A)
        val GOLD2_DEEP = Color(0xA9, 0x7B, 0x22)
        val GOLD2_SOFT = Color(0xFC, 0xF4, 0xE1)
        val CORAL = Color(0xD2, 0x59, 0x4A)
        val CORAL_SOFT = Color(0xFB, 0xEC, 0xE9)
        val MIST = Color(0xF4, 0xF6, 0xFA)
        val MIST_2 = Color(0xEC, 0xF0, 0xF6)
        val LINE2 = Color(0xE1, 0xE7, 0xEF)
        val INK2 = Color(0x16, 0x23, 0x3C)
        val MUTED2 = Color(0x66, 0x74, 0x8C)

        fun mix(first: Color, second: Color, t: Float): Color {
            val f = t.coerceIn(0f, 1f)
            return Color(
                (first.red + (second.red - first.red) * f).roundToInt().coerceIn(0, 255),
                (first.green + (second.green - first.green) * f).roundToInt().coerceIn(0, 255),
                (first.blue + (second.blue - first.blue) * f).roundToInt().coerceIn(0, 255),
            )
        }

        fun gradeFill(grade: String?): Color = when (grade?.trim()?.uppercase()) {
            "EE" -> MINT
            "ME" -> BLUE_SOFT
            "AE" -> AMBER_SOFT
            "BE", "DE" -> RED_SOFT
            else -> MINT_SOFT
        }

        fun gradeInk(grade: String?): Color = when (grade?.trim()?.uppercase()) {
            "EE" -> TEAL_DARK
            "ME" -> BLUE_INK
            "AE" -> AMBER
            "BE", "DE" -> RED
            else -> MUTED
        }

        fun levelName(grade: String?): String = when (grade?.trim()?.uppercase()) {
            "EE" -> "Exceeding Expectations"
            "ME" -> "Meeting Expectations"
            "AE" -> "Approaching Expectations"
            "BE" -> "Below Expectations"
            "DE" -> "Developing"
            else -> ""
        }

        fun shortLevel(grade: String?): String = when (grade?.trim()?.uppercase()) {
            "EE" -> "Exceeding"
            "ME" -> "Meeting"
            "AE" -> "Approaching"
            "BE" -> "Below"
            "DE" -> "Developing"
            else -> ""
        }

        fun levelStars(grade: String?): Int = when (grade?.trim()?.uppercase()) {
            "EE" -> 5
            "ME" -> 4
            "AE" -> 3
            "BE" -> 2
            "DE" -> 1
            else -> 0
        }

        fun pct(value: Double): String = String.format(Locale.US, "%.1f%%", value)
        fun oneDecimal(value: Double): String = String.format(Locale.US, "%.1f", value)
        fun twoDecimals(value: Double): String = String.format(Locale.US, "%.2f", value)

        fun parseColor(hex: String?, fallback: Color): Color {
            val value = hex?.trim()?.removePrefix("#") ?: return fallback
            if (value.length != 6) return fallback
            return runCatching { Color(value.toInt(16)) }.getOrDefault(fallback)
        }

        /**
         * Keeps Unicode letters (embedded fonts handle accented names such as Ngũgĩ) but
         * normalises control characters and typographic punctuation to report-safe forms.
         */
        fun sanitize(value: String?): String {
            if (value.isNullOrEmpty()) return ""
            val out = StringBuilder(value.length)
            var index = 0
            while (index < value.length) {
                val ch = value[index]
                val code = ch.code
                when {
                    code == 0x2026 -> out.append("...")
                    code in 0x2018..0x201B -> out.append('\'')
                    code in 0x201C..0x201F -> out.append('"')
                    code == 0x2013 || code == 0x2014 || code == 0x2212 -> out.append('-')
                    code == 0x00A0 -> out.append(' ')
                    code == 9 || code == 10 || code == 13 -> out.append(' ')
                    code < 32 || code == 0x7F -> Unit
                    Character.isHighSurrogate(ch) -> {
                        if (index + 1 < value.length && Character.isLowSurrogate(value[index + 1])) {
                            out.append(ch).append(value[index + 1])
                            index++
                        }
                    }
                    Character.isLowSurrogate(ch) -> Unit
                    else -> out.append(ch)
                }
                index++
            }
            return out.toString()
        }

        /** Last-resort text for a font that cannot show a Unicode glyph. */
        fun asciiFallback(value: String): String =
            value.map { if (it.code in 32..126) it else '?' }.joinToString("")
    }
}
