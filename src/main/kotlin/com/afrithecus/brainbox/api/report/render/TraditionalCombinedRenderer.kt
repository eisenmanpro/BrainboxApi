package com.afrithecus.brainbox.api.report.render

import com.afrithecus.brainbox.api.report.GradeTableSpec
import com.afrithecus.brainbox.api.report.ReportProperties
import org.apache.pdfbox.pdmodel.PDDocument
import org.springframework.stereotype.Component
import java.io.ByteArrayOutputStream

/**
 * TRADITIONAL_COMBINED renderer (plan A9a, section 2.5B, decision 12/16/19/21).
 *
 * One ranked list of every learner in the grade, in a portrait or landscape document chosen from
 * the exam's subject count by [ReportTableLayout] (never hardcoded, F36): compact band, one-line
 * strip, the repeated two-row header with a SCORE / % / LVL triple per subject, POS / NAME /
 * TOTAL (sum of subject percentages) / LVL / MEAN, greedy page filling from [ReportTableLayout.plan]
 * (only the final page is short and carries the compact totals block), the session-band legend,
 * the EXAM OFFICE horizontal stamp and "Page N of M".
 *
 * Learners with a missing subject or a zero subject total are not ranked and contribute nothing to
 * positions, totals or means; they are appended in "STUDENTS WITH INCOMPLETE MARKS" pages with the
 * coordinator mark-correction note (decision 15). There is no class column and no duplicate rank
 * column (F4/F24).
 */
@Component
class TraditionalCombinedRenderer(
    private val properties: ReportProperties = ReportProperties(),
) {

    fun render(spec: GradeTableSpec): ByteArray {
        val model = TraditionalReportModel.combined(spec)
        return PDDocument().use { document ->
            document.documentInformation.title = spec.title
            val theme = ReportTheme(document)
            val logo = ReportImages.brandingLogo(document, spec.branding.logoUrl, properties.embedLogo)
            if (model.subjects.isEmpty()) {
                TraditionalListPainter.emptyPage(
                    document,
                    theme,
                    spec.branding,
                    model,
                    logo,
                    "No marks have been recorded for this exam yet.",
                    model.combinedStripLeft,
                    "0 ranked",
                )
            } else {
                writePages(document, theme, spec.branding, model, logo)
            }
            val out = ByteArrayOutputStream()
            document.save(out)
            out.toByteArray()
        }
    }

    private fun writePages(
        document: PDDocument,
        theme: ReportTheme,
        branding: com.afrithecus.brainbox.api.report.ReportBranding,
        model: TraditionalListModel,
        logo: org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject?,
    ) {
        val layout = model.layout
        val plans = layout.plan(model.rankedCount)
        val incompletePages = layout.incompletePages(model.incompleteCount)
        val pageTotal = plans.size + incompletePages
        var pageNo = 0
        var start = 0
        plans.forEachIndexed { planIndex, plan ->
            pageNo++
            TraditionalListPainter.beginPage(document, theme, branding, model, logo).use { canvas ->
                val left = model.combinedStripLeft + (if (planIndex > 0) "  (continued)" else "")
                val right = "${model.rankedCount} ranked  \u00b7  ${model.incompleteCount} incomplete  \u00B7  ${model.classCount} classes"
                TraditionalListPainter.strip(canvas, model, left, right)
                TraditionalListPainter.header(canvas, model)
                for (offset in 0 until plan.rows) {
                    TraditionalListPainter.row(
                        canvas,
                        model,
                        model.ranked[start + offset],
                        offset,
                        model.layout.tableTop(),
                        incomplete = false,
                        showClassTag = false,
                    )
                }
                if (plan.totals) {
                    val blockTop = layout.tableTop() + layout.headerH + plan.rows * layout.rowH + 6f
                    TraditionalListPainter.totalsBlock(
                        canvas,
                        model,
                        blockTop,
                        "TOTALS  \u00b7  " + rankedLearnerCount(model.rankedCount),
                        lastDocumentPage = pageNo == pageTotal,
                    )
                }
                TraditionalListPainter.footer(canvas, model, pageNo, pageTotal)
            }
            start += plan.rows
        }
        if (model.incompleteCount > 0) {
            TraditionalListPainter.incompleteSection(
                document,
                theme,
                branding,
                model,
                logo,
                model.incomplete,
                pageNo,
                pageTotal,
                scopeLabel = null,
                showClassTag = false,
            )
        }
    }
}
