package com.afrithecus.brainbox.api.content.validation

import com.afrithecus.brainbox.api.content.entity.ContentUnitEntity
import com.afrithecus.brainbox.api.content.repository.ConceptRepository
import com.afrithecus.brainbox.api.content.repository.ContentUnitQuestionRepository
import com.afrithecus.brainbox.api.content.repository.ContentUnitRepository
import com.afrithecus.brainbox.api.content.repository.ContentUnitStepRepository
import com.afrithecus.brainbox.api.content.repository.CurriculumMapRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Phase 7.4b: resolves the content to inspect, runs every [ContentValidator] and
 * aggregates a confidence score. The score is 1.0 minus a severity penalty
 * (BLOCKER 1.0, WARNING 0.1, INFO 0.02), clamped to 0..1; any BLOCKER forces 0.0 so a
 * blocked item can never clear an auto-approve threshold.
 */
@Service
class ContentValidationService(
    private val units: ContentUnitRepository,
    private val steps: ContentUnitStepRepository,
    private val questions: ContentUnitQuestionRepository,
    private val concepts: ConceptRepository,
    private val curriculumMaps: CurriculumMapRepository,
    private val validators: List<ContentValidator>,
) {

    @Transactional(readOnly = true)
    fun validate(contentType: String, contentId: UUID): ValidationReport {
        val type = contentType.trim().uppercase()
        if (type != CONTENT_TYPE_UNIT) {
            return blocked("UNSUPPORTED_TYPE", "validation is only implemented for UNIT content")
        }

        val unit = units.findById(contentId).orElse(null)
            ?: return blocked("CONTENT_NOT_FOUND", "content unit not found")

        val unitSteps = steps.findAllByUnitIdOrderByOrderIndexAsc(unit.id)
        val unitQuestions = questions.findAllByUnitIdOrderByOrderIndexAsc(unit.id)
        val concept = unit.conceptId?.let { concepts.findById(it).orElse(null) }
        val mapping = unit.conceptId?.let { conceptId ->
            val all = curriculumMaps.findAllByConceptIdOrderBySortOrderAsc(conceptId)
            all.firstOrNull { it.gradeLevel.equals(unit.gradeLevel, ignoreCase = true) }
                ?: all.firstOrNull { it.gradeLevel.equals("ALL", ignoreCase = true) }
                ?: all.firstOrNull()
        }

        val ctx = ValidationContext(type, unit, unitSteps, unitQuestions, concept, mapping)
        val findings = validators.flatMap { it.validate(ctx) }
        val blockers = findings.any { it.severity == FindingSeverity.BLOCKER }
        val penalty = findings.sumOf { finding ->
            when (finding.severity) {
                FindingSeverity.BLOCKER -> BLOCKER_PENALTY
                FindingSeverity.WARNING -> WARNING_PENALTY
                FindingSeverity.INFO -> INFO_PENALTY
            }
        }
        val score = if (blockers) 0.0 else (1.0 - penalty).coerceIn(0.0, 1.0)
        return ValidationReport(score = score, findings = findings, blockers = blockers)
    }

    private fun blocked(code: String, message: String): ValidationReport =
        ValidationReport(
            score = 0.0,
            findings = listOf(ValidationFinding(FindingSeverity.BLOCKER, code, message)),
            blockers = true,
        )

    companion object {
        const val CONTENT_TYPE_UNIT = "UNIT"
        private const val BLOCKER_PENALTY = 1.0
        private const val WARNING_PENALTY = 0.1
        private const val INFO_PENALTY = 0.02
    }
}
