package com.afrithecus.brainbox.api.content.validation

import com.afrithecus.brainbox.api.content.ContentTaskTypes
import org.springframework.stereotype.Component

/**
 * Structure: a titled unit. The minimum-step rule is a lesson rule - a lesson/readable
 * unit must break the explanation into at least three steps, while an assessment
 * (QUIZ/EXAM/ASSESSMENT) is measured by its questions and may legitimately have zero
 * lesson steps. Every step that does exist must still carry a non-blank body. The
 * final-step question warning is also a lesson rule: an assessment's questions need
 * not be attached to a step at all, so only a non-assessment unit can trip it.
 */
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

        // A quiz is not a lesson: only a non-assessment unit keeps the three-step bar.
        if (!ContentTaskTypes.isAssessment(ctx.unit.taskType) && ctx.steps.size < MIN_STEPS) {
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
        } else if (ctx.steps.isNotEmpty() && !ContentTaskTypes.isAssessment(ctx.unit.taskType)) {
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
        /** The BrainBox lesson standard needs a broken-down explanation, not one paragraph. */
        const val MIN_STEPS = 3
    }
}
