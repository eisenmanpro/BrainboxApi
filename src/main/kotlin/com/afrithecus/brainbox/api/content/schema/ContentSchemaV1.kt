package com.afrithecus.brainbox.api.content.schema

import com.afrithecus.brainbox.api.content.validation.FindingSeverity
import tools.jackson.databind.JsonNode

/** One way the generated JSON failed the content schema. */
data class SchemaViolation(
    val severity: FindingSeverity,
    val code: String,
    val message: String,
)

/**
 * Content JSON schema v1 (docs/PHASE7_AGENT_ARCHITECTURE.md): the versioned
 * contract every provider response must satisfy before it is mapped and cached.
 *
 * It is deliberately structural: it checks the JSON shape (object, fields,
 * types, the question-type enum) and nothing semantic. Answer-key correctness,
 * structure minimums and curriculum fit stay in the validator chain, so a shape
 * violation fails the provider call outright while a content-quality issue still
 * routes to the human exception queue. A future contract is a new object next to
 * this one added to [SUPPORTED]; existing units keep the version that produced
 * them.
 */
object ContentSchemaV1 {

    const val VERSION = "ke-cbc-content-v1"

    val SUPPORTED: Set<String> = setOf(VERSION)

    /** The question shapes the generation prompt may return. */
    private val QUESTION_TYPES = setOf(
        "MULTIPLE_CHOICE",
        "TRUE_FALSE",
        "SHORT_ANSWER",
        "MATCHING",
        "ESSAY",
    )

    private val TOP_LEVEL = setOf(
        "body",
        "steps",
        "questions",
        "confidence",
        "sourceUrls",
        "license",
    )

    private val STEP_FIELDS = setOf("orderIndex", "title", "body", "figureSvg")

    fun validate(node: JsonNode?): List<SchemaViolation> {
        if (node == null || !node.isObject) {
            return listOf(blocker("SCHEMA_ROOT_NOT_OBJECT", "the response must be a JSON object"))
        }
        val violations = mutableListOf<SchemaViolation>()

        // Unknown top-level fields are drift worth logging, never a hard failure.
        node.properties().forEach { entry ->
            if (entry.key !in TOP_LEVEL) {
                violations += SchemaViolation(
                    FindingSeverity.INFO,
                    "SCHEMA_UNKNOWN_FIELD",
                    "unknown top-level field '" + entry.key + "'",
                )
            }
        }

        node.get("body")?.takeIf { !it.isNull }?.let {
            if (!it.isString) violations += mismatch("body", "string")
        }
        node.get("confidence")?.takeIf { !it.isNull }?.let {
            if (!it.isNumber) violations += mismatch("confidence", "number")
        }
        node.get("license")?.takeIf { !it.isNull }?.let {
            if (!it.isString) violations += mismatch("license", "string")
        }
        node.get("sourceUrls")?.takeIf { !it.isNull }?.let { urls ->
            if (!urls.isArray || urls.any { !it.isString }) violations += mismatch("sourceUrls", "an array of strings")
        }

        node.get("steps")?.takeIf { !it.isNull }?.let { steps ->
            if (!steps.isArray) {
                violations += mismatch("steps", "an array")
            } else {
                steps.forEachIndexed { index, step -> validateStep(index, step, violations) }
            }
        }
        node.get("questions")?.takeIf { !it.isNull }?.let { questions ->
            if (!questions.isArray) {
                violations += mismatch("questions", "an array")
            } else {
                questions.forEachIndexed { index, question -> validateQuestion(index, question, violations) }
            }
        }
        return violations
    }

    private fun validateStep(index: Int, step: JsonNode, out: MutableList<SchemaViolation>) {
        if (!step.isObject) {
            out += blocker("SCHEMA_STEP_NOT_OBJECT", "steps[" + index + "] must be an object")
            return
        }
        STEP_FIELDS.forEach { field ->
            val value = step.get(field) ?: return@forEach
            if (value.isNull) return@forEach
            val ok = if (field == "orderIndex") value.isNumber else value.isString
            if (!ok) out += blocker("SCHEMA_TYPE_MISMATCH", "steps[" + index + "]." + field + " has the wrong type")
        }
    }

    private fun validateQuestion(index: Int, question: JsonNode, out: MutableList<SchemaViolation>) {
        if (!question.isObject) {
            out += blocker("SCHEMA_QUESTION_NOT_OBJECT", "questions[" + index + "] must be an object")
            return
        }
        val type = question.get("type")
        if (type == null || !type.isString || type.asString().isBlank()) {
            out += blocker("SCHEMA_QUESTION_TYPE_MISSING", "questions[" + index + "].type is required")
        } else if (type.asString().trim().uppercase() !in QUESTION_TYPES) {
            out += blocker(
                "SCHEMA_QUESTION_TYPE_INVALID",
                "questions[" + index + "].type '" + type.asString() + "' is not a known question type",
            )
        }
        val text = question.get("text")
        if (text == null || !text.isString || text.asString().isBlank()) {
            out += blocker("SCHEMA_QUESTION_TEXT_MISSING", "questions[" + index + "].text is required")
        }
        question.get("options")?.takeIf { !it.isNull }?.let { options ->
            if (!options.isArray || options.any { !it.isString }) {
                out += blocker("SCHEMA_OPTIONS_INVALID", "questions[" + index + "].options must be an array of strings")
            }
        }
    }

    private fun blocker(code: String, message: String) = SchemaViolation(FindingSeverity.BLOCKER, code, message)

    private fun mismatch(field: String, expected: String) =
        blocker("SCHEMA_TYPE_MISMATCH", "'" + field + "' must be " + expected)
}
