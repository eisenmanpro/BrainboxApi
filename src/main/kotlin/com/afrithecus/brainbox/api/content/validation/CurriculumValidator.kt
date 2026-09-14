package com.afrithecus.brainbox.api.content.validation

import org.springframework.stereotype.Component

/** Curriculum: the unit resolves to a concept and a national curriculum mapping. */
@Component
class CurriculumValidator : ContentValidator {

    override val name: String = "curriculum"

    override fun validate(ctx: ValidationContext): List<ValidationFinding> {
        val findings = mutableListOf<ValidationFinding>()

        if (ctx.unit.conceptId == null) {
            findings += ValidationFinding(
                FindingSeverity.BLOCKER, "CURRICULUM_CONCEPT_MISSING", "the unit has no concept"
            )
            return findings
        }
        if (ctx.concept == null) {
            findings += ValidationFinding(
                FindingSeverity.BLOCKER, "CURRICULUM_CONCEPT_UNKNOWN", "the unit concept does not resolve"
            )
        }
        if (ctx.curriculumMapping == null) {
            findings += ValidationFinding(
                FindingSeverity.BLOCKER,
                "CURRICULUM_MAP_MISSING",
                "no curriculum mapping for concept " + ctx.unit.conceptId,
            )
        }

        return findings
    }
}
