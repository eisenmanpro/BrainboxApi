package com.afrithecus.brainbox.api.content.validation

import org.springframework.stereotype.Component

/** Structure: a titled unit of at least three explained steps with a final check. */
@Component
class StructureValidator : ContentValidator {

    override val name: String = "structure"

    override fun validate(ctx: ValidationContext): List<ValidationFinding> {
        val findings = mutableListOf<ValidationFinding>()

        if (ctx.unit.title.isNullOrBlank()) {
            findings += ValidationFinding(
                FindingSeverity.BLOCKER, "STRUCTURE_TITLE_MISSING", "the unit has no title"
            )
        }

        if (ctx.steps.size < MIN_STEPS) {
            findings += ValidationFinding(
                FindingSeverity.BLOCKER,
                "STRUCTURE_STEPS_TOO_FEW",
                "expected at least " + MIN_STEPS + " steps, found " + ctx.steps.size,
            )
        }

        ctx.steps.filter { it.body.isNullOrBlank() }.forEach { step ->
            findings += ValidationFinding(
                FindingSeverity.BLOCKER,
                "STRUCTURE_STEP_BODY_MISSING",
                "step " + step.orderIndex + " has no body",
            )
        }

        if (ctx.questions.isEmpty()) {
            findings += ValidationFinding(
                FindingSeverity.WARNING, "STRUCTURE_NO_QUESTIONS", "the unit has no questions"
            )
        } else {
            val finalStep = ctx.steps.maxByOrNull { it.orderIndex }
            if (finalStep != null && ctx.questions.none { it.stepId == finalStep.id }) {
                findings += ValidationFinding(
                    FindingSeverity.WARNING,
                    "STRUCTURE_FINAL_STEP_NO_QUESTIONS",
                    "no question is attached to the final step",
                )
            }
        }

        return findings
    }

    companion object {
        /** The BrainBox standard needs a broken-down explanation, not one paragraph. */
        const val MIN_STEPS = 3
    }
}
