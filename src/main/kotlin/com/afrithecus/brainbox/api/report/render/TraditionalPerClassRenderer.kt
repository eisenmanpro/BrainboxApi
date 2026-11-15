package com.afrithecus.brainbox.api.report.render

import com.afrithecus.brainbox.api.report.GradeTableSpec
import com.afrithecus.brainbox.api.report.ReportBranding
import com.afrithecus.brainbox.api.report.ReportProperties
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject
import org.springframework.stereotype.Component
import java.io.ByteArrayOutputStream
import java.util.Locale

/**
 * TRADITIONAL_PER_CLASS_TABLES renderer (plan A9b/A26, section 2.5C, decisions 12/16/19/21).
 *
 * One PDF per class (decision 19): the caller passes a single class tag and the document contains
 * exactly that class's ranked rows, its class totals and **its own** incomplete-mark learners - no
 * other class's row, name or tag ever appears. A null tag is the lab/preview "All" document that
 * renders every class sequentially (the production job bundles one single-class PDF per class
 * instead, A26).
 *
 * Within-class positions are recomputed here defensively (total desc, name asc) because the
 * service's per-class ranking is still grade-wide (F5 waiting on D11). The class header strip
 * carries the class tag, the resolved class teacher (blank teacher = clause omitted, never a blank
 * label), ranked/incomplete counts and the class mean of complete learners. Orientation and page
 * filling come from the same [ReportTableLayout] as the combined renderer, so the two documents of
 * one exam always agree (section 2.5H).
 */
@Component
class TraditionalPerClassRenderer(
    private val properties: ReportProperties = ReportProperties(),
) {

    /** Class tags of the document, in class-name order (the All bundle order fallback). */
    fun classTags(spec: GradeTableSpec): List<String> = TraditionalReportModel.classTags(spec)

    fun render(spec: GradeTableSpec, classTag: String?): ByteArray {
        return PDDocument().use { document ->
            document.documentInformation.title = spec.title
            val theme = ReportTheme(document)
            val logo = ReportImages.brandingLogo(document, spec.branding.logoUrl, properties.embedLogo)
            val tags = classTags(spec)
            if (tags.isEmpty()) {
                val model = TraditionalReportModel.combined(spec)
                TraditionalListPainter.emptyPage(
                    document,
                    theme,
                    spec.branding,
                    model,
                    logo,
                    "No classes have been recorded for this exam yet.",
                    model.gradeLevel.uppercase(Locale.US) + "  \u00b7  CLASS RESULTS",
                    null,
                )
            } else {
                val selected = if (classTag.isNullOrBlank()) {
                    tags
                } else {
                    listOf(TraditionalReportModel.resolveClassTag(spec, classTag))
                }
                writePages(document, theme, spec.branding, logo, spec, selected)
            }
            val out = ByteArrayOutputStream()
            document.save(out)
            out.toByteArray()
        }
    }

    private fun writePages(
        document: PDDocument,
        theme: ReportTheme,
        branding: ReportBranding,
        logo: PDImageXObject?,
        spec: GradeTableSpec,
        classTags: List<String>,
    ) {
        val models = classTags.map { TraditionalReportModel.perClass(spec, it) }
        val layouts = models.map { it.layout }
        val incomplete = if (models.size == 1) {
            models[0].incomplete
        } else {
            models.flatMap { it.incomplete }.sortedWith(
                compareBy<TraditionalListRow> { it.classTag.lowercase(Locale.US) }
                    .thenBy { it.studentName.lowercase(Locale.US) }
                    .thenBy { it.studentName },
            )
        }
        val plans = models.mapIndexed { index, model -> layouts[index].plan(model.rankedCount) }
        val mainPages = plans.sumOf { it.size }
        val pageTotal = mainPages + layouts[0].incompletePages(incomplete.size)
        var pageNo = 0
        models.forEachIndexed { modelIndex, model ->
            val layout = layouts[modelIndex]
            var start = 0
            plans[modelIndex].forEachIndexed { planIndex, plan ->
                pageNo++
                TraditionalListPainter.beginPage(document, theme, branding, model, logo).use { canvas ->
                    val left = model.classStripLeft + (if (planIndex > 0) "  (continued)" else "")
                    TraditionalListPainter.strip(canvas, model, left, classStripRight(model))
                    TraditionalListPainter.header(canvas, model)
                    for (offset in 0 until plan.rows) {
                        TraditionalListPainter.row(
                            canvas,
                            model,
                            model.ranked[start + offset],
                            offset,
                            layout.tableTop(),
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
                            "CLASS TOTALS  \u00b7  ${model.classTag}  \u00b7  " + rankedLearnerCount(model.rankedCount),
                            lastDocumentPage = pageNo == pageTotal,
                        )
                    }
                    TraditionalListPainter.footer(canvas, model, pageNo, pageTotal)
                }
                start += plan.rows
            }
        }
        if (incomplete.isNotEmpty()) {
            val singleClass = models.size == 1
            TraditionalListPainter.incompleteSection(
                document,
                theme,
                branding,
                models[0],
                logo,
                incomplete,
                startPage = pageNo,
                pageTotal = pageTotal,
                scopeLabel = if (singleClass) models[0].classTag else "ALL CLASSES",
                showClassTag = !singleClass,
            )
        }
    }

    /**
     * "Class teacher: <name> - <n> ranked - <m> incomplete - class mean <x>%". An unknown teacher
     * omits the clause (section 2.5C: never print a blank label).
     */
    private fun classStripRight(model: TraditionalListModel): String {
        val parts = mutableListOf<String>()
        model.classTeacher?.let { parts += "Class teacher: $it" }
        parts += "${model.rankedCount} ranked"
        parts += "${model.incompleteCount} incomplete"
        parts += "class mean ${ReportTheme.pct(model.classMean ?: 0.0)}"
        return parts.joinToString("   \u00b7   ")
    }
}
