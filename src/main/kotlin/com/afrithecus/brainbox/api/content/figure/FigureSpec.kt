package com.afrithecus.brainbox.api.content.figure

import com.afrithecus.brainbox.api.content.schema.SchemaViolation
import com.afrithecus.brainbox.api.content.validation.FindingSeverity
import tools.jackson.databind.JsonNode

/**
 * A declarative diagram description (Phase 7.5). The model authors one of these
 * and the server renders it to SVG, so no model markup ever reaches a learner.
 * Every field is optional except [kind]; the validator enforces the per-kind
 * requirements.
 */
data class FigureSpec(
    val kind: String = "",
    val title: String? = null,
    val caption: String? = null,
    val xLabel: String? = null,
    val yLabel: String? = null,
    /** BAR categories. */
    val categories: List<String>? = null,
    /** BAR values, parallel to categories. */
    val values: List<Double>? = null,
    /** TABLE column headers. */
    val headers: List<String>? = null,
    /** TABLE rows, each aligned to headers. */
    val rows: List<List<String>>? = null,
    /** FLOW steps, in order. */
    val steps: List<String>? = null,
    /** FLOW: draw a return arrow from the last step to the first. */
    val cyclic: Boolean = false,
)

/**
 * The closed vocabulary of figure kinds and its structural rules. A figure is a
 * small JSON object; an unknown kind or a missing field is a BLOCKER, so the
 * provider response fails before the spec is rendered.
 */
object FigureSpecs {

    /** Diagram-spec contract version, carried in the projected figure payloads. */
    const val VERSION = 1

    /** Kinds this contract allows. Adding a kind is a code change, not a promise. */
    val KINDS: Set<String> = setOf("TABLE", "BAR", "FLOW")

    fun validate(node: JsonNode?): List<SchemaViolation> {
        if (node == null || !node.isObject) {
            return listOf(violation("FIGURE_NOT_OBJECT", "figure must be an object"))
        }
        val out = mutableListOf<SchemaViolation>()
        val kind = node.get("kind")?.asString()?.trim()?.uppercase()
        if (kind.isNullOrEmpty()) {
            out += violation("FIGURE_KIND_MISSING", "figure.kind is required")
            return out
        }
        if (kind !in KINDS) {
            out += violation("FIGURE_KIND_INVALID", "figure.kind '" + kind + "' is not supported")
            return out
        }
        when (kind) {
            "BAR" -> {
                val categories = stringList(node, "categories")
                val values = numberList(node, "values")
                if (categories.isNullOrEmpty()) {
                    out += violation("FIGURE_CATEGORIES_MISSING", "BAR figure requires a non-empty categories array")
                }
                if (values.isNullOrEmpty()) {
                    out += violation("FIGURE_VALUES_MISSING", "BAR figure requires a non-empty values array")
                }
                if (categories != null && values != null && categories.size != values.size) {
                    out += violation("FIGURE_LENGTH_MISMATCH", "BAR categories and values must be the same length")
                }
            }
            "TABLE" -> {
                val headers = stringList(node, "headers")
                if (headers.isNullOrEmpty()) {
                    out += violation("FIGURE_HEADERS_MISSING", "TABLE figure requires a non-empty headers array")
                }
                val rows = node.get("rows")
                if (rows != null && !rows.isNull) {
                    val valid = rows.isArray && rows.all { row -> row.isArray && row.all { cell -> cell.isString } }
                    if (!valid) {
                        out += violation("FIGURE_ROWS_INVALID", "TABLE rows must be an array of string arrays")
                    }
                }
            }
            "FLOW" -> {
                val steps = stringList(node, "steps")
                if (steps.isNullOrEmpty()) {
                    out += violation("FIGURE_STEPS_MISSING", "FLOW figure requires a non-empty steps array")
                }
            }
        }
        return out
    }

    private fun stringList(node: JsonNode, field: String): List<String>? {
        val value = node.get(field) ?: return null
        if (value.isNull || !value.isArray) return null
        val out = ArrayList<String>(value.size())
        for (index in 0 until value.size()) out += value.get(index).asString()
        return out
    }

    private fun numberList(node: JsonNode, field: String): List<Double>? {
        val value = node.get(field) ?: return null
        if (value.isNull || !value.isArray) return null
        val out = ArrayList<Double>(value.size())
        for (index in 0 until value.size()) out += value.get(index).asDouble()
        return out
    }

    /**
     * Builds a [FigureSpec] from a validated spec node. [validate] has already
     * rejected an unknown kind and a missing required field, so this only reads.
     */
    fun parse(node: JsonNode): FigureSpec = FigureSpec(
        kind = node.get("kind")?.asString()?.trim()?.uppercase().orEmpty(),
        title = stringValue(node, "title"),
        caption = stringValue(node, "caption"),
        xLabel = stringValue(node, "xLabel"),
        yLabel = stringValue(node, "yLabel"),
        categories = stringList(node, "categories"),
        values = numberList(node, "values"),
        headers = stringList(node, "headers"),
        rows = rows(node),
        steps = stringList(node, "steps"),
        cyclic = node.get("cyclic")?.asBoolean() ?: false,
    )

    private fun stringValue(node: JsonNode, field: String): String? =
        node.get(field)?.takeIf { it.isString }?.asString()?.takeIf { it.isNotBlank() }

    private fun rows(node: JsonNode): List<List<String>>? {
        val value = node.get("rows") ?: return null
        if (value.isNull || !value.isArray) return null
        val out = ArrayList<List<String>>(value.size())
        for (index in 0 until value.size()) {
            val row = value.get(index)
            if (!row.isArray) continue
            val cells = ArrayList<String>(row.size())
            for (cell in 0 until row.size()) cells += row.get(cell).asString()
            out += cells
        }
        return out
    }

    private fun violation(code: String, message: String) =
        SchemaViolation(FindingSeverity.BLOCKER, code, message)
}
