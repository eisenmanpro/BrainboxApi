package com.afrithecus.brainbox.api.report

import com.afrithecus.brainbox.api.report.render.CbcClassRenderer
import com.afrithecus.brainbox.api.report.render.CbcStudentRenderer
import com.afrithecus.brainbox.api.report.render.GradeAnalysisRenderer
import com.afrithecus.brainbox.api.report.render.TeacherPerformanceRenderer
import com.afrithecus.brainbox.api.report.render.TemplateRenderer
import com.afrithecus.brainbox.api.report.render.TraditionalCombinedRenderer
import com.afrithecus.brainbox.api.report.render.TraditionalPerClassRenderer
import com.afrithecus.brainbox.api.report.render.TraditionalStudentRenderer
import org.springframework.stereotype.Component

/**
 * Dispatches a render-ready [ReportRenderSpec] to its report-book renderer (plan A0/A12).
 *
 * Every report type now renders through a dedicated renderer on the v1 card/list foundation;
 * the legacy canvas that served the first report pass is gone. Renderers own their PDDocument
 * and return the PDF bytes, so this class stays a pure dispatcher.
 */
@Component
class ReportRenderer(private val properties: ReportProperties) {

    private val combinedRenderer = TraditionalCombinedRenderer(properties)
    private val perClassRenderer = TraditionalPerClassRenderer(properties)
    private val analysisRenderer = GradeAnalysisRenderer(properties)
    private val traditionalStudentRenderer = TraditionalStudentRenderer(properties)
    private val cbcStudentRenderer = CbcStudentRenderer(properties)
    private val cbcClassRenderer = CbcClassRenderer(properties)
    private val teacherPerformanceRenderer = TeacherPerformanceRenderer(properties)
    private val templateRenderer = TemplateRenderer(properties)

    fun render(spec: ReportRenderSpec): ByteArray = when (spec) {
        is GradeTableSpec -> if (spec.perClass) {
            throw IllegalArgumentException("Per-class tables need a class tag; use renderArtifact()")
        } else {
            combinedRenderer.render(spec)
        }
        is GradeAnalysisSpec -> analysisRenderer.render(spec)
        is TraditionalStudentsSpec -> traditionalStudentRenderer.render(spec)
        is CbcStudentsSpec -> cbcStudentRenderer.render(spec)
        is CbcClassSpec -> cbcClassRenderer.render(spec)
        is TeacherPerformanceSpec -> teacherPerformanceRenderer.render(spec)
        is TemplateSpec -> templateRenderer.render(spec)
    }

    /** Renders one planned artifact (D19); per-class artifacts carry the class tag. */
    fun renderArtifact(artifact: ReportArtifact): ByteArray {
        val spec = artifact.spec
        return if (artifact.classTag != null && spec is GradeTableSpec) {
            perClassRenderer.render(spec, artifact.classTag)
        } else {
            render(spec)
        }
    }
}
