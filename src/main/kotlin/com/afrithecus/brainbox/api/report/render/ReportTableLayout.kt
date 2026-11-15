package com.afrithecus.brainbox.api.report.render

import kotlin.math.ceil
import kotlin.math.floor

/** One planned list page: how many ranked rows it holds and whether the totals block is at its foot. */
data class PagePlan(val rows: Int, val totals: Boolean)

/**
 * Adaptive list geometry (plan A20, §2.5H): the page orientation, column widths and rows per
 * page are derived from one number — the exam's configured subject count — and shared by the
 * combined and per-class renderers so both documents of one exam always agree.
 *
 * Geometries approved in the lab (L7), 500 learners:
 *  5 subjects  portrait   41 rows, 16 pt rows, 53.5 pt triples
 *  9 subjects  landscape  31 rows, 14 pt rows, 57.1 pt triples
 * 13 subjects  landscape  35 rows, 12.5 pt rows, 41.8 pt triples (compact)
 * 17 subjects  landscape  35 rows, 34.5 pt triples (resilience fixture only, decision 21)
 */
class ReportTableLayout(val subjectCount: Int) {

    init {
        require(subjectCount > 0) { "An exam must have at least one subject" }
    }

    val landscape: Boolean = subjectCount > 5
    val compact: Boolean = subjectCount >= 10
    val extreme: Boolean = subjectCount >= 16

    val pageWidth: Float
    val pageHeight: Float
    val bandH: Float
    val stripH: Float
    val headerH: Float = 26f
    val rowH: Float
    val body: Float

    var posW: Float = 0f
        private set
    var nameW: Float = 0f
        private set
    var totalW: Float = 0f
        private set
    var lvlW: Float = 0f
        private set
    var meanW: Float = 0f
        private set
    var pairW: Float = 0f
        private set
    var scoreW: Float = 0f
        private set
    var pctW: Float = 0f
        private set
    var levelW: Float = 0f
        private set
    var rowsPerPage: Int = 0
        private set
    var blockRows: Int = 0
        private set

    val blockHeight: Float = 54f

    init {
        pageWidth = if (landscape) 841.89f else 595.28f
        pageHeight = if (landscape) 595.28f else 841.89f
        bandH = when {
            compact -> 68f
            landscape -> 76f
            else -> 84f
        }
        stripH = if (landscape) 24f else 28f
        rowH = when {
            landscape && compact -> 12.5f
            landscape -> 14f
            else -> 16f
        }
        body = if (compact) 7.5f else 8.6f
        posW = if (compact) (if (extreme) 22f else 24f) else 26f
        totalW = if (compact) (if (extreme) 42f else 46f) else 50f
        lvlW = if (compact) (if (extreme) 20f else 22f) else 24f
        meanW = if (compact) (if (extreme) 30f else 34f) else 38f
        var name = if (compact) (if (extreme) 70f else 100f) else 118f
        val usable = pageWidth - MARGIN * 2
        var pair = (usable - posW - name - totalW - lvlW - meanW) / subjectCount
        if (pair < MIN_TRIPLE_WIDTH) {
            name = (name - (MIN_TRIPLE_WIDTH - pair) * subjectCount).coerceAtLeast(70f)
            pair = (usable - posW - name - totalW - lvlW - meanW) / subjectCount
        }
        nameW = name
        pairW = pair
        scoreW = pairW * 0.40f
        pctW = pairW * 0.34f
        levelW = pairW - scoreW - pctW
        rowsPerPage = floor((pageHeight - bandH - stripH - headerH - 32f) / rowH).toInt().coerceAtLeast(10)
        blockRows = ceil(60f / rowH).toInt()
    }

    /** Top-of-table offset under the band and strip. */
    fun tableTop(): Float = bandH + stripH

    fun blockHeightFor(rows: Int): Float = rows * rowH

    /**
     * Fills every page to capacity; only the final page is short and carries the totals block.
     * The renderer never balances the tail across pages (owner review round 2).
     */
    fun plan(rowCount: Int): List<PagePlan> {
        if (rowCount == 0) return listOf(PagePlan(0, true))
        val pages = mutableListOf<PagePlan>()
        var remaining = rowCount
        while (remaining > rowsPerPage) {
            pages.add(PagePlan(rowsPerPage, false))
            remaining -= rowsPerPage
        }
        if (remaining + blockRows <= rowsPerPage) {
            pages.add(PagePlan(remaining, true))
        } else {
            val take = (rowsPerPage - blockRows).coerceAtLeast(0)
            pages.add(PagePlan(take, false))
            pages.add(PagePlan(remaining - take, true))
        }
        return pages
    }

    /** First incomplete-marks page is shorter because it carries the explanation + note. */
    fun incompleteFirstPageRows(): Int = maxOf(
        6,
        floor((pageHeight - 32f - (bandH + stripH) - 38f - headerH) / rowH).toInt(),
    )

    fun incompletePages(count: Int): Int {
        if (count <= 0) return 0
        val first = incompleteFirstPageRows()
        if (count <= first) return 1
        return 1 + ceil((count - first) / rowsPerPage.toDouble()).toInt()
    }

    companion object {
        const val MARGIN: Float = 36f
        const val MIN_TRIPLE_WIDTH: Float = 34f
    }
}
