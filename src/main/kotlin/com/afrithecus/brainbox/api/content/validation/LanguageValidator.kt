package com.afrithecus.brainbox.api.content.validation

import org.springframework.stereotype.Component

/** Language: a recognised language tag and non-empty teaching text. */
@Component
class LanguageValidator : ContentValidator {

    override val name: String = "language"

    override fun validate(ctx: ValidationContext): List<ValidationFinding> {
        val findings = mutableListOf<ValidationFinding>()

        if (ctx.unit.language.isBlank()) {
            findings += ValidationFinding(
                FindingSeverity.BLOCKER, "LANGUAGE_MISSING", "the unit has no language"
            )
        }

        val text = (ctx.unit.body.orEmpty() + " " + ctx.steps.joinToString(" ") { it.body.orEmpty() }).trim()
        if (text.isEmpty()) {
            findings += ValidationFinding(
                FindingSeverity.BLOCKER, "LANGUAGE_TEXT_EMPTY", "the unit has no teachable text"
            )
        }

        if (ctx.unit.language.isNotBlank() && ctx.unit.language.trim().lowercase() !in RECOGNISED_LANGUAGES) {
            findings += ValidationFinding(
                FindingSeverity.INFO,
                "LANGUAGE_UNRECOGNISED",
                "language '" + ctx.unit.language + "' is not yet recognised by the localisation agent",
            )
        }

        return findings
    }

    companion object {
        val RECOGNISED_LANGUAGES = setOf("en", "sw")
    }
}
