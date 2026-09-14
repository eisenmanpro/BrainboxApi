package com.afrithecus.brainbox.api.content.validation

import com.afrithecus.brainbox.api.content.entity.ConceptEntity
import com.afrithecus.brainbox.api.content.entity.ContentUnitEntity
import com.afrithecus.brainbox.api.content.entity.ContentUnitQuestionEntity
import com.afrithecus.brainbox.api.content.entity.ContentUnitStepEntity
import com.afrithecus.brainbox.api.content.entity.CurriculumMapEntity

/** Phase 7.4b validation vocabulary: severity, one finding and the aggregate report. */
enum class FindingSeverity { BLOCKER, WARNING, INFO }

data class ValidationFinding(
    val severity: FindingSeverity,
    val code: String,
    val message: String,
)

/** The validator result for one content version; blockers force the score to 0. */
data class ValidationReport(
    val score: Double,
    val findings: List<ValidationFinding>,
    val blockers: Boolean,
)

/** Everything the validators may inspect, resolved once by [ContentValidationService]. */
data class ValidationContext(
    val contentType: String,
    val unit: ContentUnitEntity,
    val steps: List<ContentUnitStepEntity>,
    val questions: List<ContentUnitQuestionEntity>,
    val concept: ConceptEntity?,
    val curriculumMapping: CurriculumMapEntity?,
)

/**
 * One deterministic check. Implementations are Spring beans; ContentValidationService
 * runs every registered validator and aggregates their findings.
 */
interface ContentValidator {
    val name: String
    fun validate(ctx: ValidationContext): List<ValidationFinding>
}
