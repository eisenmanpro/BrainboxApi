package com.afrithecus.brainbox.api.report.render

import com.afrithecus.brainbox.api.report.GradeTableSpec
import com.afrithecus.brainbox.api.report.ReportBranding
import com.afrithecus.brainbox.api.report.ReportGradingBand
import com.afrithecus.brainbox.api.traditional.model.displayName
import com.afrithecus.brainbox.api.traditional.web.StudentGradeRowDto
import com.afrithecus.brainbox.api.traditional.web.SubjectConfigDto
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject
import java.util.Locale

/**
 * Render model for the two dense traditional list documents (plan A9a/A9b, sections 2.5B/2.5C),
 * ported from the approved lab layout (template/proto/src/lab/TraditionalList.java, L1/L2/L7).
 *
 * The model is the single place where a GradeTableSpec becomes the values that get printed:
 *
 *  - subjects in exam order with the fixed decision-13 abbreviations;
 *  - complete learners (every subject present and its raw total > 0) ranked by total
 *    descending then name ascending with unique sequential positions;
 *  - incomplete learners (missing subject or a zero subject total) kept out of positions,
 *    totals and means, ordered class then name;
 *  - TOTAL = StudentGradeRowDto.totalPercentage (the sheet's G.TOTAL = sum of subject percentages),
 *    MEAN = TOTAL / subject count (1 dp), level = the server's overallGrade or, when blank, the
 *    session band resolved from MEAN (D17/D18).
 *
 * A zero COMPONENT inside a combined subject does not hide the learner: the combined subject's
 * raw total is still a real mark, so only a whole-subject zero/missing is incomplete (decision 15).
 *
 * The TraditionalListPainter in this file is the shared page furniture of both list renderers
 * (two-row header, zebra rows, totals block, incomplete section, stamp, footer). It lives here so
 * the combined and per-class documents keep sharing one geometry and paint path without touching
 * the committed foundation files.
 */
data class TraditionalSubjectColumn(
    val subjectId: String,
    val name: String,
    val abbreviation: String,
    val maxScore: Int,
)

/** One printed SCORE / % / LVL triple; present is false for a subject the learner did not sit. */
data class TraditionalSubjectCell(
    val rawScore: Int,
    val percentage: Double,
    val level: String,
    val present: Boolean,
) {
    /** A whole-subject zero total (not a zero component) - corrected through the D15 flow. */
    val isZeroTotal: Boolean get() = present && rawScore <= 0
}

/** One printed row. position is null for the incomplete table (POS prints as "-"). */
data class TraditionalListRow(
    val position: Int?,
    val studentId: String,
    val studentName: String,
    val classTag: String,
    val cells: List<TraditionalSubjectCell>,
    val total: Double,
    val mean: Double,
    val level: String,
)

/** Everything the list painters need for one document (or one class of a bundle). */
data class TraditionalListModel(
    val documentTitle: String,
    val gradeLevel: String,
    val examTitle: String,
    val termLabel: String,
    val schoolName: String,
    val layout: ReportTableLayout,
    val subjects: List<TraditionalSubjectColumn>,
    val ranked: List<TraditionalListRow>,
    val incomplete: List<TraditionalListRow>,
    val subjectMeans: List<Double>,
    val learnerTotalSum: Double,
    val overallMean: Double,
    val overallGrade: String,
    val bands: List<ReportGradingBand>,
    val classTag: String? = null,
    val classTeacher: String? = null,
    val classMean: Double? = null,
    val classCount: Int = 0,
) {
    val rankedCount: Int get() = ranked.size
    val incompleteCount: Int get() = incomplete.size
    val subjectCount: Int get() = subjects.size
    val isPerClass: Boolean get() = classTag != null

    /** Band sub-label: "Grade 5 - combined results" / "Grade 5 - Grade 5 Lion". */
    val bandGradeLabel: String
        get() = if (classTag != null) "$gradeLevel \u00b7 $classTag" else "$gradeLevel \u00b7 combined results"

    val footerLeft: String get() = "$schoolName  \u00b7  $termLabel"

    val combinedStripLeft: String
        get() = "${gradeLevel.uppercase()} COMBINED RESULTS \u2014 ${termLabel.uppercase()}"

    val classStripLeft: String
        get() = "${classTag?.uppercase()}  \u00b7  CLASS RESULTS"
}

/** Fixed decision-13 score-table abbreviations; never a wrapped/full subject name. */
object TraditionalSubjectAbbreviations {

    private val FIXED: Map<String, String> = mapOf(
        "mathematics" to "MATH",
        "maths" to "MATH",
        "math" to "MATH",
        "english" to "ENG",
        "kiswahili" to "KISW",
        "integrated science" to "INT",
        "integrated sciences" to "INT",
        "science" to "INT",
        "social studies" to "SST",
        "social study" to "SST",
        "cre" to "CRE",
        "c r e" to "CRE",
        "christian religious education" to "CRE",
        "agriculture" to "AGRI",
        "agriculture and nutrition" to "AGRI",
        "agriculture nutrition" to "AGRI",
        "agri" to "AGRI",
        "creative arts" to "CRA",
        "creative art" to "CRA",
        "physical and health education" to "PHE",
        "physical health education" to "PHE",
        "phe" to "PHE",
        "pe" to "PHE",
        "pre technical studies" to "PRETECH",
        "pretechnical studies" to "PRETECH",
        "pre technical" to "PRETECH",
        "pretechnical" to "PRETECH",
        "business studies" to "BUS",
        "business" to "BUS",
        "home science" to "HSC",
        "homescience" to "HSC",
        "computer science" to "COMP",
        "computer studies" to "COMP",
        "computers" to "COMP",
        "music" to "MUS",
        "art and craft" to "ART",
        "art craft" to "ART",
        "art" to "ART",
        "french" to "FRE",
        "german" to "GER",
    )

    /**
     * The header label for one subject. Known names use the fixed map; an unknown name falls back to
     * its first four letters in capitals (no invented full names, decision 13).
     */
    fun of(subjectName: String?): String {
        val name = subjectName?.trim().orEmpty()
        if (name.isEmpty()) return "SUBJ"
        val key = normalise(name)
        FIXED[key]?.let { return it }
        val letters = name.filter { it.isLetterOrDigit() }
        return if (letters.length >= 4) letters.take(4).uppercase(Locale.US) else letters.uppercase(Locale.US)
    }

    fun normalise(value: String): String =
        value.lowercase(Locale.US)
            .map { if (it.isLetterOrDigit()) it else ' ' }
            .joinToString("")
            .replace(Regex("\\s+"), " ")
            .trim()
}

/** Builds the render model from the already-assembled GradeTableSpec (no business logic here). */
object TraditionalReportModel {

    /** Platform defaults used when the spec carries no session snapshot (decision 20). */
    val DEFAULT_BANDS: List<ReportGradingBand> = listOf(
        ReportGradingBand("EE", 80.0),
        ReportGradingBand("ME", 65.0),
        ReportGradingBand("AE", 50.0),
        ReportGradingBand("BE", 35.0),
        ReportGradingBand("DE", 20.0),
        ReportGradingBand("E", 0.0),
    )

    /** Grade-wide combined document: every learner in the grade, one ranked sequence. */
    fun combined(spec: GradeTableSpec): TraditionalListModel = build(spec, spec.rows, classTag = null)

    /** One class only (decision 19): the renderer passes a single class tag per PDF. */
    fun perClass(spec: GradeTableSpec, classTag: String): TraditionalListModel {
        val tag = resolveClassTag(spec, classTag)
        val rows = spec.rows.filter { it.classTag.trim().equals(tag, ignoreCase = true) }
        return build(spec, rows, classTag = tag)
    }

    /**
     * Class tags that appear in the document, in class-name order (the All bundle order fallback;
     * the school catalogue order arrives with the A26 plumbing). Class-teacher keys are included so
     * a class with no mark rows still gets its own PDF.
     */
    fun classTags(spec: GradeTableSpec): List<String> {
        val fromRows = spec.rows.map { it.classTag.trim() }.filter { it.isNotEmpty() }
        val extras = spec.classTeachers.keys.map { it.trim() }
            .filter { key -> key.isNotEmpty() && fromRows.none { it.equals(key, ignoreCase = true) } }
        return (fromRows + extras)
            .distinctBy { it.lowercase(Locale.US) }
            .sortedWith(String.CASE_INSENSITIVE_ORDER)
    }

    /** Canonical tag for a filter value; unknown tags are a caller error, never a silent mix. */
    fun resolveClassTag(spec: GradeTableSpec, requested: String): String {
        val wanted = requested.trim()
        return classTags(spec).firstOrNull { it.equals(wanted, ignoreCase = true) }
            ?: throw IllegalArgumentException("Unknown class filter: $requested")
    }

    /** First matching level label for a percentage against the session/snapshot bands. */
    fun levelFor(percentage: Double, bands: List<ReportGradingBand>): String {
        if (bands.isEmpty()) return ""
        val ordered = bands.sortedByDescending { it.minPercentage }
        val match = ordered.firstOrNull { percentage >= it.minPercentage } ?: ordered.last()
        return match.label.trim().uppercase(Locale.US)
    }

    /** Subjects in the exam's own order, abbreviations from the fixed decision-13 map. */
    fun subjectColumns(spec: GradeTableSpec): List<TraditionalSubjectColumn> {
        val configured = spec.exam.subjects
        if (configured.isNotEmpty()) return configured.map { column(it) }
        // Defensive: an exam snapshot without subjects still renders from the rows it carries.
        val sample = spec.rows.firstOrNull { it.subjects.isNotEmpty() }?.subjects ?: return emptyList()
        return sample.map { result ->
            TraditionalSubjectColumn(
                subjectId = result.subjectName,
                name = result.subjectName,
                abbreviation = TraditionalSubjectAbbreviations.of(result.subjectName),
                maxScore = result.maxScore,
            )
        }
    }

    private fun column(config: SubjectConfigDto): TraditionalSubjectColumn = TraditionalSubjectColumn(
        subjectId = config.subjectId,
        name = config.name,
        abbreviation = TraditionalSubjectAbbreviations.of(config.name),
        maxScore = config.maxScore,
    )

    private fun build(
        spec: GradeTableSpec,
        rows: List<StudentGradeRowDto>,
        classTag: String?,
    ): TraditionalListModel {
        val subjects = subjectColumns(spec)
        val bands = resolveBands(spec.bands)
        val complete = ArrayList<TraditionalListRow>()
        val incomplete = ArrayList<TraditionalListRow>()
        rows.forEach { dto ->
            val cells = subjects.map { subjectCell(it, dto, bands) }
            val total = dto.totalPercentage
                .takeIf { it.isFinite() }
                ?.coerceAtLeast(0.0)
                ?: cells.sumOf { it.percentage }
            val mean = if (subjects.isEmpty()) 0.0 else total / subjects.size
            val level = dto.overallGrade.trim().takeIf { it.isNotEmpty() }
                ?: levelFor(mean, bands)
            val row = TraditionalListRow(
                position = null,
                studentId = dto.studentId,
                studentName = dto.studentName.trim(),
                classTag = dto.classTag.trim(),
                cells = cells,
                total = total,
                mean = mean,
                level = level.uppercase(Locale.US),
            )
            if (cells.all { it.present && it.rawScore > 0 }) complete += row else incomplete += row
        }

        // Unique sequential positions, total desc then name asc - the school sheet's tie rule.
        val ranked = complete
            .sortedWith(
                compareByDescending<TraditionalListRow> { it.total }
                    .thenBy { it.studentName.lowercase(Locale.US) }
                    .thenBy { it.studentName },
            )
            .mapIndexed { index, row -> row.copy(position = index + 1) }

        val subjectMeans = subjects.indices.map { index ->
            if (ranked.isEmpty()) 0.0 else ranked.sumOf { it.cells[index].percentage } / ranked.size
        }
        val learnerTotalSum = ranked.sumOf { it.total }
        val overallMean = if (ranked.isEmpty()) 0.0 else ranked.sumOf { it.mean } / ranked.size
        val classMean = if (classTag == null) null else overallMean
        val classTeacher = classTag?.let { tag ->
            spec.classTeachers.entries
                .firstOrNull { it.key.trim().equals(tag, ignoreCase = true) }
                ?.value
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
        }
        val orderedIncomplete = incomplete.sortedWith(
            compareBy<TraditionalListRow> { it.classTag.lowercase(Locale.US) }
                .thenBy { it.studentName.lowercase(Locale.US) }
                .thenBy { it.studentName },
        )

        return TraditionalListModel(
            documentTitle = spec.title,
            gradeLevel = spec.exam.gradeLevel.trim(),
            examTitle = spec.exam.title.trim(),
            termLabel = "${spec.exam.term.displayName()} ${spec.exam.year}",
            schoolName = spec.branding.schoolName.trim(),
            layout = ReportTableLayout(subjects.size.coerceAtLeast(1)),
            subjects = subjects,
            ranked = ranked,
            incomplete = orderedIncomplete,
            subjectMeans = subjectMeans,
            learnerTotalSum = learnerTotalSum,
            overallMean = overallMean,
            overallGrade = levelFor(overallMean, bands),
            bands = bands,
            classTag = classTag,
            classTeacher = classTeacher,
            classMean = classMean,
            classCount = if (classTag == null) classTags(spec).size else 0,
        )
    }

    private fun resolveBands(bands: List<ReportGradingBand>): List<ReportGradingBand> {
        val resolved = bands.filter { it.label.isNotBlank() }.sortedByDescending { it.minPercentage }
        return if (resolved.isEmpty()) DEFAULT_BANDS else resolved
    }

    private fun subjectCell(
        column: TraditionalSubjectColumn,
        row: StudentGradeRowDto,
        bands: List<ReportGradingBand>,
    ): TraditionalSubjectCell {
        val key = TraditionalSubjectAbbreviations.normalise(column.name)
        val result = row.subjects.firstOrNull {
            TraditionalSubjectAbbreviations.normalise(it.subjectName) == key
        } ?: return TraditionalSubjectCell(rawScore = 0, percentage = 0.0, level = "", present = false)

        val raw = result.rawScore.coerceAtLeast(0)
        val maxScore = if (result.maxScore > 0) result.maxScore else column.maxScore
        val percentage = when {
            result.percentage.isFinite() && result.percentage >= 0.0 -> result.percentage
            maxScore > 0 -> raw.toDouble() / maxScore * 100.0
            else -> 0.0
        }
        val level = result.grade.trim().takeIf { it.isNotEmpty() } ?: levelFor(percentage, bands)
        return TraditionalSubjectCell(raw, percentage, level.uppercase(Locale.US), present = true)
    }
}

/**
 * Page furniture shared by TraditionalCombinedRenderer and TraditionalPerClassRenderer:
 * compact band, one-line strip, the repeated two-row SCORE/%/LVL header, zebra rows, the compact
 * (54 pt) totals block with the EXAM OFFICE horizontal stamp and the grading legend, and the
 * "Students with incomplete marks" section with its own headers and repair note.
 */
/** "1 learner" / "39 learners" for the totals and incomplete labels. */
internal fun learnerCount(count: Int): String = count.toString() + " " + (if (count == 1) "learner" else "learners")

/** "1 ranked learner" / "39 ranked learners" for the totals block label. */
internal fun rankedLearnerCount(count: Int): String =
    count.toString() + " ranked " + (if (count == 1) "learner" else "learners")

internal object TraditionalListPainter {

    private const val MARGIN = ReportTableLayout.MARGIN
    private const val STRIP_CONTINUED = "  (continued)"

    fun beginPage(
        document: PDDocument,
        theme: ReportTheme,
        branding: ReportBranding,
        model: TraditionalListModel,
        logo: PDImageXObject?,
    ): ReportCanvas {
        val layout = model.layout
        val canvas = ReportCanvas(document, theme, layout.pageWidth, layout.pageHeight)
        ReportFurniture.watermark(canvas, branding)
        ReportFurniture.band(
            canvas,
            branding,
            model.bandGradeLabel,
            model.examTitle,
            model.termLabel,
            layout.bandH,
            logo,
        )
        return canvas
    }

    fun strip(canvas: ReportCanvas, model: TraditionalListModel, left: String, right: String?) {
        ReportFurniture.strip(canvas, left, right, model.layout.bandH, model.layout.stripH)
    }

    fun footer(canvas: ReportCanvas, model: TraditionalListModel, page: Int, pages: Int) {
        ReportFurniture.footer(canvas, model.footerLeft, page, pages)
    }

    /** Repeated two-row header: POS, NAME, one abbreviation over SCORE/%/LVL per subject, TOTAL/LVL/MEAN. */
    fun header(canvas: ReportCanvas, model: TraditionalListModel, tableTop: Float = model.layout.tableTop()) {
        val layout = model.layout
        val theme = canvas.theme
        val x = MARGIN
        val width = canvas.width - MARGIN * 2
        val row1H = 13f
        val row2H = layout.headerH - row1H
        val base1 = canvas.top(tableTop + row1H / 2f + 2.1f)
        val base2 = canvas.top(tableTop + row1H + row2H / 2f + 1.9f)
        val subjectX = x + layout.posW + layout.nameW
        val headerFont = if (layout.extreme) 5.0f else if (layout.compact) 5.6f else 6.2f
        val subFont = if (layout.extreme) 3.8f else if (layout.compact) 4.1f else 4.5f
        val dim = ReportTheme.mix(ReportTheme.PAPER, ReportTheme.NAVY, 0.28f)

        canvas.fillRect(x, canvas.top(tableTop + layout.headerH), width, layout.headerH, ReportTheme.NAVY)
        canvas.tracked("POS", x + 2f, base1, theme.sansBold, 5.2f, 1.0f, ReportTheme.PAPER)
        canvas.tracked("NAME", x + layout.posW + 4f, base1, theme.sansBold, 5.2f, 1.0f, ReportTheme.PAPER)
        model.subjects.forEachIndexed { index, subject ->
            val px = subjectX + index * layout.pairW
            canvas.textC(subject.abbreviation, px + layout.pairW / 2f, base1, theme.sansBold, headerFont, ReportTheme.PAPER)
            canvas.textR("SCORE", px + layout.scoreW - 2f, base2, theme.sans, subFont, dim)
            canvas.textR("%", px + layout.scoreW + layout.pctW - 2f, base2, theme.sans, subFont, dim)
            canvas.textC("LVL", px + layout.scoreW + layout.pctW + layout.levelW / 2f, base2, theme.sans, subFont, dim)
        }
        val totalX = subjectX + layout.subjectCount * layout.pairW
        canvas.textC("TOTAL", totalX + layout.totalW / 2f, base1, theme.sansBold, headerFont, ReportTheme.PAPER)
        canvas.textR("\u03a3%", totalX + layout.totalW - 2f, base2, theme.sans, subFont, dim)
        canvas.textC("LVL", totalX + layout.totalW + layout.lvlW / 2f, base1, theme.sansBold, headerFont, ReportTheme.PAPER)
        val meanX = totalX + layout.totalW + layout.lvlW
        canvas.textC("MEAN", meanX + layout.meanW / 2f, base1, theme.sansBold, headerFont, ReportTheme.PAPER)
        canvas.textR("%", meanX + layout.meanW - 2f, base2, theme.sans, subFont, dim)
        canvas.line(x, canvas.top(tableTop + row1H), x + width, canvas.top(tableTop + row1H), ReportTheme.GOLD2, 0.45f)
    }

    /**
     * One body row. Value baseline sits at the row centre (F40, lab revision row 1); a zero subject
     * score prints 0 in red and a missing subject prints "-" in the muted tint (approved lab
     * behaviour; a whole-subject zero is also what the D15 mark-correction note asks to fix).
     */
    fun row(
        canvas: ReportCanvas,
        model: TraditionalListModel,
        row: TraditionalListRow,
        indexInPage: Int,
        tableTop: Float = model.layout.tableTop(),
        incomplete: Boolean,
        showClassTag: Boolean,
    ) {
        val layout = model.layout
        val theme = canvas.theme
        val x = MARGIN
        val width = canvas.width - MARGIN * 2
        val top = tableTop + layout.headerH + indexInPage * layout.rowH
        val bottom = canvas.top(top + layout.rowH)
        if (indexInPage % 2 == 1) {
            canvas.fillRect(x, bottom, width, layout.rowH, ReportTheme.MINT_SOFT)
        }
        canvas.line(x, bottom, x + width, bottom, ReportTheme.LINE2, 0.35f)
        val base = canvas.top(top + layout.rowH / 2f + layout.body * 0.36f)

        val posX = x + layout.posW / 2f
        if (incomplete || row.position == null) {
            canvas.textC("\u2013", posX, base, theme.sans, layout.body - 0.6f, ReportTheme.MUTED)
        } else {
            canvas.textC(row.position.toString(), posX, base, theme.sans, layout.body - 0.6f, ReportTheme.MUTED)
        }

        val nameX = x + layout.posW + 5f
        if (incomplete && showClassTag) {
            val tag = "  \u00b7  " + shortClass(row.classTag)
            val tagWidth = canvas.textWidth(tag, theme.sans, 6.0f)
            val name = canvas.ellipsis(row.studentName, theme.sansBold, layout.body, layout.nameW - 8f - tagWidth)
            canvas.text(name, nameX, base, theme.sansBold, layout.body, ReportTheme.INK)
            canvas.text(tag, nameX + canvas.textWidth(name, theme.sansBold, layout.body), base, theme.sans, 6.0f, ReportTheme.MUTED)
        } else {
            canvas.text(
                canvas.ellipsis(row.studentName, theme.sansBold, layout.body, layout.nameW - 8f),
                nameX,
                base,
                theme.sansBold,
                layout.body,
                ReportTheme.INK,
            )
        }

        val subjectX = x + layout.posW + layout.nameW
        val scoreFont = if (layout.extreme) 6.5f else if (layout.compact) 7.2f else layout.body - 0.2f
        val pctFont = if (layout.extreme) 5.9f else if (layout.compact) 6.6f else layout.body - 0.6f
        row.cells.forEachIndexed { index, cell ->
            val px = subjectX + index * layout.pairW
            if (!cell.present) {
                val dim = ReportTheme.mix(ReportTheme.MUTED, ReportTheme.PAPER, 0.35f)
                canvas.textR("\u2013", px + layout.scoreW - 3f, base, theme.sans, layout.body - 0.2f, dim)
                canvas.textR("\u2013", px + layout.scoreW + layout.pctW - 3f, base, theme.sans, layout.body - 0.4f, dim)
                canvas.textC(
                    "\u2013",
                    px + layout.scoreW + layout.pctW + layout.levelW / 2f,
                    base,
                    theme.sans,
                    layout.body - 0.6f,
                    dim,
                )
                return@forEachIndexed
            }
            canvas.textR(
                cell.rawScore.toString(),
                px + layout.scoreW - 2f,
                base,
                theme.sansBold,
                scoreFont,
                if (cell.rawScore == 0) ReportTheme.RED else ReportTheme.INK,
            )
            canvas.textR(
                roundedPercentage(cell.percentage),
                px + layout.scoreW + layout.pctW - 2f,
                base,
                theme.sans,
                pctFont,
                ReportTheme.MUTED,
            )
            val chipCenterX = px + layout.scoreW + layout.pctW + layout.levelW / 2f
            if (layout.compact) {
                canvas.textR(
                    cell.level.ifBlank { "-" },
                    px + layout.pairW - 1f,
                    base,
                    theme.sansBold,
                    if (layout.extreme) 4.8f else 5.3f,
                    ReportTheme.gradeInk(cell.level),
                )
            } else {
                ReportFurniture.levelCell(
                    canvas,
                    cell.level.ifBlank { "-" },
                    chipCenterX,
                    canvas.top(top + layout.rowH / 2f),
                    minOf(20f, layout.levelW - 2f),
                    minOf(11f, layout.rowH - 3f),
                )
            }
        }

        val totalX = subjectX + layout.subjectCount * layout.pairW
        canvas.textR(
            ReportTheme.oneDecimal(row.total),
            totalX + layout.totalW - 3f,
            base,
            theme.sansBold,
            layout.body - 0.1f,
            ReportTheme.NAVY,
        )
        val levelX = totalX + layout.totalW + layout.lvlW / 2f
        if (layout.compact) {
            canvas.textC(
                row.level.ifBlank { "-" },
                levelX,
                base,
                theme.sansBold,
                5.7f,
                ReportTheme.gradeInk(row.level),
            )
        } else {
            ReportFurniture.levelCell(
                canvas,
                row.level.ifBlank { "-" },
                levelX,
                canvas.top(top + layout.rowH / 2f),
                minOf(22f, layout.lvlW - 2f),
                minOf(11f, layout.rowH - 3f),
            )
        }
        val meanX = totalX + layout.totalW + layout.lvlW
        canvas.textR(
            ReportTheme.oneDecimal(row.mean),
            meanX + layout.meanW - 3f,
            base,
            theme.sansBold,
            layout.body - 0.1f,
            ReportTheme.TEAL_DARK,
        )
    }

    /**
     * Compact (54 pt) totals block: per-subject mean %, overall mean and grade, sum of learner totals,
     * the formula line, the session-band legend and - on the document's last page - the horizontal
     * EXAM OFFICE stamp.
     */
    fun totalsBlock(
        canvas: ReportCanvas,
        model: TraditionalListModel,
        top: Float,
        label: String,
        lastDocumentPage: Boolean,
    ) {
        val layout = model.layout
        val theme = canvas.theme
        val x = MARGIN
        val width = canvas.width - MARGIN * 2
        val blockHeight = layout.blockHeight
        canvas.fillRound(x, canvas.top(top + blockHeight), width, blockHeight, 3f, ReportTheme.MINT_SOFT)
        canvas.line(x, canvas.top(top + 1f), x + width, canvas.top(top + 1f), ReportTheme.GOLD2, 1.4f)
        canvas.tracked(label.uppercase(Locale.US), x + 8f, canvas.top(top + 9f), theme.sansBold, 5.5f, 1.1f, ReportTheme.MUTED)
        canvas.textR(
            "${model.incompleteCount} incomplete excluded",
            x + width - 8f,
            canvas.top(top + 9f),
            theme.sans,
            5.0f,
            ReportTheme.MUTED,
        )
        val subjectX = x + layout.posW + layout.nameW
        model.subjectMeans.forEachIndexed { index, mean ->
            val centerX = subjectX + index * layout.pairW + layout.pairW / 2f
            canvas.textC(
                model.subjects[index].abbreviation,
                centerX,
                canvas.top(top + 20f),
                theme.sansBold,
                4.5f,
                ReportTheme.MUTED,
            )
            canvas.textC(
                ReportTheme.oneDecimal(mean),
                centerX,
                canvas.top(top + 30f),
                theme.sansBold,
                if (layout.compact) 5.6f else 6.2f,
                ReportTheme.INK,
            )
        }
        canvas.text(
            "OVERALL   mean ${ReportTheme.pct(model.overallMean)}   \u00b7   grade ${model.overallGrade}" +
                "   \u00b7   \u03a3 learner totals ${ReportTheme.oneDecimal(model.learnerTotalSum)}",
            x + 8f,
            canvas.top(top + 42f),
            theme.sansBold,
            if (layout.compact) 5.6f else 6.1f,
            ReportTheme.NAVY,
        )
        canvas.text(
            "subject % = raw \u00f7 subject max \u00d7 100   \u00b7   TOTAL = \u03a3 subject %" +
                "   \u00b7   MEAN = TOTAL \u00f7 ${layout.subjectCount}   \u00b7   incomplete marks excluded",
            x + 8f,
            canvas.top(top + 51f),
            theme.sans,
            4.7f,
            ReportTheme.MUTED,
        )
        if (lastDocumentPage) {
            ReportFurniture.horizontalStamp(canvas, x + width - 126f, canvas.top(top + 51f))
        }
        val legendY = if (top + blockHeight + 30f < canvas.height - 30f) {
            canvas.top(top + blockHeight + 14f)
        } else {
            36f
        }
        ReportFurniture.legend(canvas, x, legendY, model.bands.map { GradingBand(it.label, it.minPercentage) })
    }

    /**
     * "STUDENTS WITH INCOMPLETE MARKS" pages: their own repeated headers, the repair note on the
     * first page, POS "-", the learner's class tag in the all-class review document only, and the
     * horizontal stamp on the top-right of the header when this section is the document's last.
     */
    fun incompleteSection(
        document: PDDocument,
        theme: ReportTheme,
        branding: ReportBranding,
        model: TraditionalListModel,
        logo: PDImageXObject?,
        rows: List<TraditionalListRow>,
        startPage: Int,
        pageTotal: Int,
        scopeLabel: String?,
        showClassTag: Boolean,
    ) {
        if (rows.isEmpty()) return
        val layout = model.layout
        var pageNo = startPage
        var index = 0
        var pageIndex = 0
        while (index < rows.size) {
            pageNo++
            val capacity = if (pageIndex == 0) layout.incompleteFirstPageRows() else layout.rowsPerPage
            val count = minOf(capacity, rows.size - index)
            val lastDocumentPage = pageNo == pageTotal
            beginPage(document, theme, branding, model, logo).use { canvas ->
                val scope = scopeLabel?.takeIf { it.isNotBlank() }
                    ?.let { "  \u00b7  ${it.uppercase(Locale.US)}" }
                    .orEmpty()
                val left = "STUDENTS WITH INCOMPLETE MARKS" +
                    (if (pageIndex > 0) STRIP_CONTINUED else "") + scope
                strip(
                    canvas,
                    model,
                    left,
                    learnerCount(rows.size) + "  \u00b7  not ranked  \u00b7  excluded from totals",
                )
                var tableTop = layout.tableTop()
                if (pageIndex == 0) {
                    val noteWidth = canvas.width - MARGIN * 2 - (if (lastDocumentPage) 130f else 0f)
                    val note = canvas.wrap(
                        "At least one subject has no mark (0 or a blank). These learners are not " +
                            "positioned and are excluded from the totals and means above.",
                        theme.sans,
                        6.3f,
                        noteWidth,
                    ) + canvas.wrap(
                        "Teachers: if a learner appears here by mistake, request a mark correction " +
                            "from your coordinator \u2014 a genuine zero must be corrected to 1 " +
                            "(or the mark actually scored).",
                        theme.sans,
                        6.3f,
                        noteWidth,
                    )
                    var noteY = tableTop + 11f
                    note.forEach { line ->
                        canvas.text(line, MARGIN, canvas.top(noteY), theme.sans, 6.3f, ReportTheme.MUTED)
                        noteY += 9f
                    }
                    if (lastDocumentPage) {
                        ReportFurniture.horizontalStamp(
                            canvas,
                            canvas.width - MARGIN - 118f,
                            canvas.top(tableTop + 30f),
                        )
                    }
                    tableTop = noteY + 4f
                }
                header(canvas, model, tableTop)
                for (offset in 0 until count) {
                    row(
                        canvas,
                        model,
                        rows[index + offset],
                        offset,
                        tableTop,
                        incomplete = true,
                        showClassTag = showClassTag,
                    )
                }
                footer(canvas, model, pageNo, pageTotal)
            }
            index += count
            pageIndex++
        }
    }

    /** Single page for an exam whose spec carries no subjects/rows: honest empty document state. */
    fun emptyPage(
        document: PDDocument,
        theme: ReportTheme,
        branding: ReportBranding,
        model: TraditionalListModel,
        logo: PDImageXObject?,
        message: String,
        left: String,
        right: String?,
    ) {
        beginPage(document, theme, branding, model, logo).use { canvas ->
            strip(canvas, model, left, right)
            canvas.text(message, MARGIN, canvas.top(model.layout.tableTop() + 40f), theme.sans, 10f, ReportTheme.MUTED)
            footer(canvas, model, 1, 1)
        }
    }

    private fun roundedPercentage(value: Double): String =
        Math.round(value.coerceIn(0.0, 100.0)).toString()

    private fun shortClass(tag: String): String {
        val index = tag.lastIndexOf(' ')
        return if (index < 0) tag else tag.substring(index + 1)
    }
}
