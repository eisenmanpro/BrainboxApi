package com.afrithecus.brainbox.api.content.validation

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.core.io.ClassPathResource
import org.springframework.core.io.Resource
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.util.regex.Pattern

/**
 * Phase 7.5d: the deterministic, fail-closed safety gate. It runs over every
 * teachable text field of a content unit and emits a BLOCKER per matched safety
 * category. Because [ContentValidationService] forces score 0.0 and blockers=true
 * on any BLOCKER, a match can never clear the 7.5c auto-approval bar: the unit is
 * withheld and routed to the human exception queue instead.
 *
 * The rule set is versioned resource data, not code: [RESOURCE] is loaded once at
 * construction and compiled to [Pattern]s. If the resource is missing, blank,
 * unparseable or carries a bad regex, [loadRules] returns null and the validator
 * emits [CODE_CONFIG_MISSING] for every unit, so nothing auto-approves. There is
 * deliberately no policy toggle: the gate is always on.
 *
 * Scope: unit title and body, every step title and body, and every question text,
 * option, correct answer and explanation. figure_svg is deliberately NOT scanned:
 * it is renderer markup (paths, coordinates, style attributes), not teachable
 * prose, so scanning it would flag geometry numbers while catching no extra prose.
 */
@Component
class SafetyValidator(
    private val objectMapper: ObjectMapper,
    private val rules: SafetyRuleSet?,
) : ContentValidator {

    /** Spring path: load the versioned blocklist once from its classpath location. */
    @Autowired
    constructor(objectMapper: ObjectMapper) : this(objectMapper, loadRules(objectMapper, ClassPathResource(RESOURCE)))

    /** Test seam: load from an explicit resource so the fail-closed path is exercisable. */
    constructor(objectMapper: ObjectMapper, resource: Resource) : this(objectMapper, loadRules(objectMapper, resource))

    override val name: String = "safety"

    override fun validate(ctx: ValidationContext): List<ValidationFinding> {
        val configured = rules ?: return listOf(configMissing())

        val findings = mutableListOf<ValidationFinding>()
        teachableTexts(ctx).forEach { field ->
            val text = field.text
            if (text.isNullOrBlank()) return@forEach
            configured.categories.forEach { category ->
                if (category.patterns.any { it.matcher(text).find() }) {
                    findings += ValidationFinding(
                        FindingSeverity.BLOCKER,
                        category.code,
                        "safety category " + category.code + " matched " + field.label + ": " + category.description,
                    )
                }
            }
        }
        return findings
    }

    /** Every prose field the safety gate owns; figure_svg is intentionally absent. */
    private fun teachableTexts(ctx: ValidationContext): List<TeachableText> {
        val fields = mutableListOf<TeachableText>()
        fields += TeachableText("unit title", ctx.unit.title)
        fields += TeachableText("unit body", ctx.unit.body)

        ctx.steps.forEach { step ->
            fields += TeachableText("step " + step.orderIndex + " title", step.title)
            fields += TeachableText("step " + step.orderIndex + " body", step.body)
        }

        ctx.questions.forEach { question ->
            fields += TeachableText("question " + question.orderIndex + " text", question.text)
            options(question.options).forEachIndexed { index, option ->
                fields += TeachableText("question " + question.orderIndex + " option " + index, option)
            }
            fields += TeachableText("question " + question.orderIndex + " correct answer", question.correctAnswer)
            fields += TeachableText("question " + question.orderIndex + " explanation", question.explanation)
        }
        return fields
    }

    private fun options(raw: String?): List<String> =
        raw?.let {
            runCatching { objectMapper.readValue(it, Array<String>::class.java).toList() }.getOrNull()
        }.orEmpty()

    private fun configMissing(): ValidationFinding = ValidationFinding(
        FindingSeverity.BLOCKER,
        CODE_CONFIG_MISSING,
        "safety blocklist " + RESOURCE + " is missing, blank or unparseable; failing closed",
    )

    private data class TeachableText(val label: String, val text: String?)

    companion object {
        const val RESOURCE: String = "safety/blocklist-v1.json"
        const val CODE_CONFIG_MISSING: String = "SAFETY_CONFIG_MISSING"

        /**
         * Reads and compiles the blocklist exactly once. Returns null for a missing,
         * blank, empty or malformed resource (including any invalid regex), which the
         * validator turns into a fail-closed SAFETY_CONFIG_MISSING blocker.
         */
        internal fun loadRules(objectMapper: ObjectMapper, resource: Resource): SafetyRuleSet? = try {
            if (!resource.exists()) {
                null
            } else {
                resource.inputStream.use { stream ->
                    compileRules(objectMapper.readValue(stream, SafetyBlocklistFile::class.java))
                }
            }
        } catch (ex: Exception) {
            null
        }

        private fun compileRules(file: SafetyBlocklistFile?): SafetyRuleSet? {
            if (file == null) return null
            val version = file.version?.trim().orEmpty()
            if (version.isEmpty()) return null

            val categories = file.categories.orEmpty().map { category ->
                val code = category.code?.trim().orEmpty()
                val description = category.description?.trim().orEmpty()
                val patterns = category.patterns.orEmpty()
                    .filter { it.isNotBlank() }
                    .map { Pattern.compile(it, Pattern.CASE_INSENSITIVE) }
                SafetyRuleCategory(code, description.ifEmpty { code }, patterns)
            }
            if (categories.any { it.code.isEmpty() || it.patterns.isEmpty() }) return null
            if (categories.isEmpty()) return null
            return SafetyRuleSet(version, categories)
        }
    }
}

/** A compiled, versioned safety rule set. */
data class SafetyRuleSet(val version: String, val categories: List<SafetyRuleCategory>)

/** One safety category: the stable code, a human description and its compiled regexes. */
data class SafetyRuleCategory(val code: String, val description: String, val patterns: List<Pattern>)

/** Raw blocklist JSON shape; every field is optional so a blank document fails closed. */
internal data class SafetyBlocklistFile(
    val version: String? = null,
    val categories: List<SafetyBlocklistCategory>? = null,
)

internal data class SafetyBlocklistCategory(
    val code: String? = null,
    val description: String? = null,
    val patterns: List<String>? = null,
)
