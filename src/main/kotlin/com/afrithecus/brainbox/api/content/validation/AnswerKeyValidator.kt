package com.afrithecus.brainbox.api.content.validation

import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

/** Answer keys: every question has one, and a multiple-choice key is exactly one option. */
@Component
class AnswerKeyValidator(private val mapper: ObjectMapper) : ContentValidator {

    override val name: String = "answer_key"

    override fun validate(ctx: ValidationContext): List<ValidationFinding> {
        val findings = mutableListOf<ValidationFinding>()

        ctx.questions.forEach { question ->
            val key = question.correctAnswer?.trim().orEmpty()
            if (key.isEmpty()) {
                findings += ValidationFinding(
                    FindingSeverity.BLOCKER,
                    "ANSWER_KEY_MISSING",
                    "question " + question.orderIndex + " has no answer key",
                )
                return@forEach
            }
            if (question.qType.trim().uppercase() in MULTIPLE_CHOICE_TYPES) {
                val matches = options(question.options).count { it.trim().equals(key, ignoreCase = true) }
                if (matches != 1) {
                    findings += ValidationFinding(
                        FindingSeverity.BLOCKER,
                        "ANSWER_KEY_NOT_AN_OPTION",
                        "question " + question.orderIndex + " answer key must match exactly one option",
                    )
                }
            }
        }

        return findings
    }

    private fun options(raw: String?): List<String> =
        raw?.let {
            runCatching { mapper.readValue(it, Array<String>::class.java).toList() }.getOrNull()
        }.orEmpty()

    companion object {
        val MULTIPLE_CHOICE_TYPES = setOf("MCQ", "MULTIPLE_CHOICE")
    }
}
