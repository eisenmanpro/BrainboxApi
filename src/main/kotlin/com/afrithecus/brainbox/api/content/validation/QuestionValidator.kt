package com.afrithecus.brainbox.api.content.validation

import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

/** Questions: every item has a prompt; a multiple-choice item has >= 2 distinct options. */
@Component
class QuestionValidator(private val mapper: ObjectMapper) : ContentValidator {

    override val name: String = "questions"

    override fun validate(ctx: ValidationContext): List<ValidationFinding> {
        val findings = mutableListOf<ValidationFinding>()

        ctx.questions.forEach { question ->
            if (question.text.isBlank()) {
                findings += ValidationFinding(
                    FindingSeverity.BLOCKER,
                    "QUESTION_PROMPT_MISSING",
                    "question " + question.orderIndex + " has no prompt",
                )
            }
            if (isMultipleChoice(question.qType) && options(question.options).distinct().size < 2) {
                findings += ValidationFinding(
                    FindingSeverity.BLOCKER,
                    "QUESTION_OPTIONS_FEW",
                    "question " + question.orderIndex + " needs at least two distinct options",
                )
            }
        }

        return findings
    }

    private fun isMultipleChoice(type: String): Boolean =
        type.trim().uppercase() in MULTIPLE_CHOICE_TYPES

    private fun options(raw: String?): List<String> =
        raw?.let {
            runCatching { mapper.readValue(it, Array<String>::class.java).toList() }.getOrNull()
        }.orEmpty()

    companion object {
        val MULTIPLE_CHOICE_TYPES = setOf("MCQ", "MULTIPLE_CHOICE")
    }
}
