package com.afrithecus.brainbox.api.content.figure

import com.afrithecus.brainbox.api.content.schema.SchemaViolation
import com.afrithecus.brainbox.api.content.validation.FindingSeverity
import tools.jackson.databind.JsonNode

/** A named data series, used by LINE and BAR charts. */
data class FigureSeries(
    val name: String? = null,
    val values: List<Double> = emptyList(),
)

/** One slice of a PIE chart. */
data class FigureSlice(
    val label: String = "",
    val value: Double = 0.0,
)

/**
 * A marked point on a NUMBER_LINE. [open] draws a hollow dot, which is how a
 * strict inequality endpoint (x < 3) is shown.
 */
data class FigureMark(
    val value: Double = 0.0,
    val label: String? = null,
    val open: Boolean = false,
)

/** A shaded interval on a NUMBER_LINE, drawn as a bar over the axis. */
data class FigureInterval(
    val from: Double = 0.0,
    val to: Double = 0.0,
    val label: String? = null,
)

/** The model-space window a GEOMETRY figure is drawn in (math orientation, y up). */
data class FigureViewBox(
    val minX: Double = 0.0,
    val minY: Double = 0.0,
    val width: Double = 10.0,
    val height: Double = 10.0,
)

/**
 * One primitive of a GEOMETRY figure. This is a closed set: the renderer draws
 * exactly these and nothing else, so a spec can describe a construction without
 * ever carrying arbitrary markup. Coordinates are pairs in the model's own units.
 */
data class FigureElement(
    /** SEGMENT, POLYGON, CIRCLE, ARC, POINT, ANGLE, RIGHT_ANGLE or LABEL. */
    val type: String = "",
    val from: List<Double>? = null,
    val to: List<Double>? = null,
    val at: List<Double>? = null,
    val center: List<Double>? = null,
    val points: List<List<Double>>? = null,
    val radius: Double? = null,
    val startAngle: Double? = null,
    val endAngle: Double? = null,
    val vertex: List<Double>? = null,
    val text: String? = null,
    val label: String? = null,
    /** SOLID (default) or DASHED. */
    val style: String? = null,
    /** POLYGON and RECT only: shade the interior. */
    val filled: Boolean = false,
    /** ELLIPSE only: the vertical radius. */
    val radiusY: Double? = null,
    /** BEZIER only: the two cubic control points. */
    val control1: List<Double>? = null,
    val control2: List<Double>? = null,
)

/** One node of a TREE; [parent] is another node's [id], or null for the root. */
data class FigureNode(
    val id: String = "",
    val label: String = "",
    val parent: String? = null,
)

/** One set of a VENN diagram: its label and the items inside its own region. */
data class FigureSet(
    val label: String? = null,
    val items: List<String> = emptyList(),
)

/**
 * A declarative figure description (Phase 7.5). The model authors one of these
 * and the server renders it, so no model markup ever reaches a learner. Every
 * field is optional except [kind]; the validator enforces the per-kind rules.
 */
data class FigureSpec(
    val kind: String = "",
    val title: String? = null,
    val caption: String? = null,
    val xLabel: String? = null,
    val yLabel: String? = null,
    // BAR.
    val categories: List<String>? = null,
    val values: List<Double>? = null,
    /** BAR: print the value above each bar. */
    val showValues: Boolean = false,
    // TABLE.
    val headers: List<String>? = null,
    val rows: List<List<String>>? = null,
    // FLOW.
    val steps: List<String>? = null,
    val cyclic: Boolean = false,
    // LINE.
    val series: List<FigureSeries>? = null,
    // PIE.
    val slices: List<FigureSlice>? = null,
    val donut: Boolean = false,
    // NUMBER_LINE.
    val min: Double? = null,
    val max: Double? = null,
    val step: Double? = null,
    val marks: List<FigureMark>? = null,
    val intervals: List<FigureInterval>? = null,
    // GEOMETRY.
    val viewBox: FigureViewBox? = null,
    val elements: List<FigureElement>? = null,
    val grid: Boolean = false,
    // TREE.
    val nodes: List<FigureNode>? = null,
    // VENN.
    val sets: List<FigureSet>? = null,
    val shared: List<String>? = null,
)

/**
 * The closed vocabulary of figure kinds and its structural rules. A figure is a
 * small JSON object; an unknown kind or a missing field is a BLOCKER, so the
 * provider response fails before the spec is rendered.
 */
object FigureSpecs {

    /** Diagram-spec contract version, carried in the projected figure payloads. */
    const val VERSION = 2

    /** Kinds this contract allows. Adding a kind is a code change, not a promise. */
    val KINDS: Set<String> = setOf(
        "TABLE",
        "BAR",
        "FLOW",
        "LINE",
        "PIE",
        "NUMBER_LINE",
        "GEOMETRY",
        "TREE",
        "VENN",
    )

    /** The closed primitive set a GEOMETRY figure may compose. */
    val ELEMENT_TYPES: Set<String> = setOf(
        "SEGMENT",
        "POLYGON",
        "CIRCLE",
        "ARC",
        "POINT",
        "ANGLE",
        "RIGHT_ANGLE",
        "RECT",
        "ELLIPSE",
        "ARROW",
        "BEZIER",
        "LABEL",
    )

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
            "BAR" -> validateBar(node, out)
            "LINE" -> validateLine(node, out)
            "PIE" -> validatePie(node, out)
            "TABLE" -> validateTable(node, out)
            "FLOW" -> validateFlow(node, out)
            "NUMBER_LINE" -> validateNumberLine(node, out)
            "GEOMETRY" -> validateGeometry(node, out)
            "TREE" -> validateTree(node, out)
            "VENN" -> validateVenn(node, out)
        }
        return out
    }

    private fun validateBar(node: JsonNode, out: MutableList<SchemaViolation>) {
        val categories = stringList(node, "categories")
        val values = numberList(node, "values")
        if (categories.isNullOrEmpty()) {
            out += violation("FIGURE_CATEGORIES_MISSING", "BAR figure requires a non-empty categories array")
        }
        if (values.isNullOrEmpty()) {
            out += violation("FIGURE_VALUES_MISSING", "BAR figure requires a non-empty values array")
        }
        if (values != null && values.any { !it.isFinite() }) {
            out += violation("FIGURE_VALUES_INVALID", "BAR values must be finite numbers")
        }
        if (categories != null && values != null && categories.size != values.size) {
            out += violation("FIGURE_LENGTH_MISMATCH", "BAR categories and values must be the same length")
        }
    }

    private fun validateLine(node: JsonNode, out: MutableList<SchemaViolation>) {
        val categories = stringList(node, "categories")
        if (categories.isNullOrEmpty()) {
            out += violation("FIGURE_CATEGORIES_MISSING", "LINE figure requires a non-empty categories array")
        }
        val series = node.get("series")
        if (series == null || series.isNull || !series.isArray || series.size() == 0) {
            out += violation("FIGURE_SERIES_MISSING", "LINE figure requires a non-empty series array")
            return
        }
        for (index in 0 until series.size()) {
            val entry = series.get(index)
            if (!entry.isObject) {
                out += violation("FIGURE_SERIES_INVALID", "LINE series[" + index + "] must be an object")
                continue
            }
            val values = numberList(entry, "values")
            if (values.isNullOrEmpty()) {
                out += violation("FIGURE_SERIES_INVALID", "LINE series[" + index + "] requires a non-empty values array")
                continue
            }
            if (values.any { !it.isFinite() }) {
                out += violation("FIGURE_VALUES_INVALID", "LINE series[" + index + "] values must be finite")
            }
            if (categories != null && categories.isNotEmpty() && values.size != categories.size) {
                out += violation(
                    "FIGURE_LENGTH_MISMATCH",
                    "LINE series[" + index + "] values must match categories length",
                )
            }
        }
    }

    private fun validatePie(node: JsonNode, out: MutableList<SchemaViolation>) {
        val slices = node.get("slices")
        if (slices == null || slices.isNull || !slices.isArray || slices.size() == 0) {
            out += violation("FIGURE_SLICES_MISSING", "PIE figure requires a non-empty slices array")
            return
        }
        var total = 0.0
        for (index in 0 until slices.size()) {
            val entry = slices.get(index)
            val value = entry.get("value")?.takeIf { it.isNumber }?.asDouble()
            if (value == null || !value.isFinite() || value < 0.0) {
                out += violation("FIGURE_SLICE_VALUE_INVALID", "PIE slices[" + index + "].value must be a number >= 0")
            } else {
                total += value
            }
        }
        if (total <= 0.0) {
            out += violation("FIGURE_SLICES_EMPTY_TOTAL", "PIE slices must sum to more than zero")
        }
    }

    private fun validateTable(node: JsonNode, out: MutableList<SchemaViolation>) {
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

    private fun validateFlow(node: JsonNode, out: MutableList<SchemaViolation>) {
        val steps = stringList(node, "steps")
        if (steps.isNullOrEmpty()) {
            out += violation("FIGURE_STEPS_MISSING", "FLOW figure requires a non-empty steps array")
        }
    }

    private fun validateNumberLine(node: JsonNode, out: MutableList<SchemaViolation>) {
        val min = node.get("min")?.takeIf { it.isNumber }?.asDouble()
        val max = node.get("max")?.takeIf { it.isNumber }?.asDouble()
        if (min == null || max == null || !min.isFinite() || !max.isFinite()) {
            out += violation("FIGURE_RANGE_MISSING", "NUMBER_LINE requires numeric min and max")
        } else if (min >= max) {
            out += violation("FIGURE_RANGE_INVALID", "NUMBER_LINE min must be less than max")
        }
        val step = node.get("step")?.takeIf { it.isNumber }?.asDouble()
        if (step != null && (!step.isFinite() || step <= 0.0)) {
            out += violation("FIGURE_STEP_INVALID", "NUMBER_LINE step must be a positive number")
        }
        node.get("marks")?.takeIf { it.isArray }?.let { marks ->
            for (index in 0 until marks.size()) {
                val value = marks.get(index).get("value")?.takeIf { it.isNumber }?.asDouble()
                if (value == null || !value.isFinite()) {
                    out += violation("FIGURE_MARK_INVALID", "NUMBER_LINE marks[" + index + "].value must be a finite number")
                }
            }
        }
    }

    private fun validateGeometry(node: JsonNode, out: MutableList<SchemaViolation>) {
        val viewBox = node.get("viewBox")
        if (viewBox == null || !viewBox.isObject) {
            out += violation("FIGURE_VIEWBOX_MISSING", "GEOMETRY requires a viewBox object")
        } else {
            val width = viewBox.get("width")?.asDouble() ?: 0.0
            val height = viewBox.get("height")?.asDouble() ?: 0.0
            if (!width.isFinite() || width <= 0.0 || !height.isFinite() || height <= 0.0) {
                out += violation("FIGURE_VIEWBOX_INVALID", "GEOMETRY viewBox width and height must be positive")
            }
        }
        val elements = node.get("elements")
        if (elements == null || elements.isNull || !elements.isArray || elements.size() == 0) {
            out += violation("FIGURE_ELEMENTS_MISSING", "GEOMETRY requires a non-empty elements array")
            return
        }
        for (index in 0 until elements.size()) {
            validateElement(index, elements.get(index), out)
        }
    }

    private fun validateTree(node: JsonNode, out: MutableList<SchemaViolation>) {
        val nodes = node.get("nodes")
        if (nodes == null || nodes.isNull || !nodes.isArray || nodes.size() == 0) {
            out += violation("FIGURE_NODES_MISSING", "TREE requires a non-empty nodes array")
            return
        }
        val ids = mutableSetOf<String>()
        val parents = mutableListOf<String?>()
        for (index in 0 until nodes.size()) {
            val entry = nodes.get(index)
            if (!entry.isObject) {
                out += violation("FIGURE_NODE_INVALID", "TREE nodes[" + index + "] must be an object")
                continue
            }
            val id = entry.get("id")?.asString()?.takeIf { it.isNotBlank() }
            val label = entry.get("label")?.asString()?.takeIf { it.isNotBlank() }
            if (id == null || label == null) {
                out += violation("FIGURE_NODE_INVALID", "TREE nodes[" + index + "] requires id and label")
            } else {
                ids += id
            }
            parents += entry.get("parent")?.takeIf { it.isString }?.asString()?.takeIf { it.isNotBlank() }
        }
        parents.filterNotNull().forEach { parent ->
            if (parent !in ids) out += violation("FIGURE_PARENT_UNKNOWN", "TREE parent '" + parent + "' is not a node id")
        }
        val roots = parents.count { it == null }
        if (roots == 0) out += violation("FIGURE_TREE_NO_ROOT", "TREE needs one node with no parent")
        if (roots > 1) out += violation("FIGURE_TREE_MULTIPLE_ROOTS", "TREE needs exactly one root node")
    }

    private fun validateVenn(node: JsonNode, out: MutableList<SchemaViolation>) {
        val sets = node.get("sets")
        if (sets == null || sets.isNull || !sets.isArray) {
            out += violation("FIGURE_SETS_MISSING", "VENN requires a sets array")
            return
        }
        if (sets.size() !in 2..3) {
            out += violation("FIGURE_SETS_INVALID", "VENN supports two or three sets")
            return
        }
        for (index in 0 until sets.size()) {
            val entry = sets.get(index)
            if (!entry.isObject) {
                out += violation("FIGURE_SET_INVALID", "VENN sets[" + index + "] must be an object")
                continue
            }
            val items = entry.get("items")
            if (items != null && !items.isNull && (!items.isArray || items.any { !it.isString })) {
                out += violation("FIGURE_SET_INVALID", "VENN sets[" + index + "].items must be an array of strings")
            }
        }
        val shared = node.get("shared")
        if (shared != null && !shared.isNull && (!shared.isArray || shared.any { !it.isString })) {
            out += violation("FIGURE_SHARED_INVALID", "VENN shared must be an array of strings")
        }
    }

    private fun validateElement(index: Int, element: JsonNode, out: MutableList<SchemaViolation>) {
        val where = "GEOMETRY elements[" + index + "]"
        if (!element.isObject) {
            out += violation("FIGURE_ELEMENT_INVALID", where + " must be an object")
            return
        }
        val type = element.get("type")?.asString()?.trim()?.uppercase()
        if (type.isNullOrEmpty() || type !in ELEMENT_TYPES) {
            out += violation("FIGURE_ELEMENT_TYPE_INVALID", where + ".type is not a known primitive")
            return
        }
        val ok = when (type) {
            "SEGMENT" -> hasPoint(element, "from") && hasPoint(element, "to")
            "POLYGON" -> (pointList(element, "points")?.size ?: 0) >= 3
            "CIRCLE" -> hasPoint(element, "center") && (element.get("radius")?.asDouble() ?: 0.0) > 0.0
            "ARC" -> hasPoint(element, "center") && (element.get("radius")?.asDouble() ?: 0.0) > 0.0 &&
                element.get("startAngle")?.isNumber == true && element.get("endAngle")?.isNumber == true
            "POINT" -> hasPoint(element, "at")
            "ANGLE", "RIGHT_ANGLE" -> hasPoint(element, "vertex") && hasPoint(element, "from") && hasPoint(element, "to")
            "RECT", "ARROW" -> hasPoint(element, "from") && hasPoint(element, "to")
            "ELLIPSE" -> hasPoint(element, "center") &&
                (element.get("radius")?.asDouble() ?: 0.0) > 0.0 && (element.get("radiusY")?.asDouble() ?: 0.0) > 0.0
            "BEZIER" -> hasPoint(element, "from") && hasPoint(element, "to") &&
                hasPoint(element, "control1") && hasPoint(element, "control2")
            "LABEL" -> hasPoint(element, "at") && !element.get("text")?.asString().isNullOrBlank()
            else -> false
        }
        if (!ok) {
            out += violation("FIGURE_ELEMENT_INVALID", where + " (" + type + ") is missing a required coordinate")
        }
    }

    /** Builds a [FigureSpec] from a validated spec node. */
    fun parse(node: JsonNode): FigureSpec = FigureSpec(
        kind = node.get("kind")?.asString()?.trim()?.uppercase().orEmpty(),
        title = stringValue(node, "title"),
        caption = stringValue(node, "caption"),
        xLabel = stringValue(node, "xLabel"),
        yLabel = stringValue(node, "yLabel"),
        categories = stringList(node, "categories"),
        values = numberList(node, "values"),
        showValues = node.get("showValues")?.asBoolean() ?: false,
        headers = stringList(node, "headers"),
        rows = rows(node),
        steps = stringList(node, "steps"),
        cyclic = node.get("cyclic")?.asBoolean() ?: false,
        series = series(node),
        slices = slices(node),
        donut = node.get("donut")?.asBoolean() ?: false,
        min = doubleValue(node, "min"),
        max = doubleValue(node, "max"),
        step = doubleValue(node, "step"),
        marks = marks(node),
        intervals = intervals(node),
        viewBox = viewBox(node),
        elements = elements(node),
        grid = node.get("grid")?.asBoolean() ?: false,
        nodes = nodes(node),
        sets = sets(node),
        shared = stringList(node, "shared"),
    )

    // ------------------------------------------------------------- parsing

    private fun series(node: JsonNode): List<FigureSeries>? {
        val array = array(node, "series") ?: return null
        val out = ArrayList<FigureSeries>(array.size())
        for (index in 0 until array.size()) {
            val entry = array.get(index)
            out += FigureSeries(name = stringValue(entry, "name"), values = numberList(entry, "values").orEmpty())
        }
        return out
    }

    private fun slices(node: JsonNode): List<FigureSlice>? {
        val array = array(node, "slices") ?: return null
        val out = ArrayList<FigureSlice>(array.size())
        for (index in 0 until array.size()) {
            val entry = array.get(index)
            out += FigureSlice(
                label = stringValue(entry, "label").orEmpty(),
                value = doubleValue(entry, "value") ?: 0.0,
            )
        }
        return out
    }

    private fun marks(node: JsonNode): List<FigureMark>? {
        val array = array(node, "marks") ?: return null
        val out = ArrayList<FigureMark>(array.size())
        for (index in 0 until array.size()) {
            val entry = array.get(index)
            out += FigureMark(
                value = doubleValue(entry, "value") ?: 0.0,
                label = stringValue(entry, "label"),
                open = entry.get("open")?.asBoolean() ?: false,
            )
        }
        return out
    }

    private fun intervals(node: JsonNode): List<FigureInterval>? {
        val array = array(node, "intervals") ?: return null
        val out = ArrayList<FigureInterval>(array.size())
        for (index in 0 until array.size()) {
            val entry = array.get(index)
            out += FigureInterval(
                from = doubleValue(entry, "from") ?: 0.0,
                to = doubleValue(entry, "to") ?: 0.0,
                label = stringValue(entry, "label"),
            )
        }
        return out
    }

    private fun viewBox(node: JsonNode): FigureViewBox? {
        val value = node.get("viewBox") ?: return null
        if (!value.isObject) return null
        return FigureViewBox(
            minX = doubleValue(value, "minX") ?: 0.0,
            minY = doubleValue(value, "minY") ?: 0.0,
            width = doubleValue(value, "width") ?: 10.0,
            height = doubleValue(value, "height") ?: 10.0,
        )
    }

    private fun elements(node: JsonNode): List<FigureElement>? {
        val array = array(node, "elements") ?: return null
        val out = ArrayList<FigureElement>(array.size())
        for (index in 0 until array.size()) {
            val entry = array.get(index)
            out += FigureElement(
                type = entry.get("type")?.asString()?.trim()?.uppercase().orEmpty(),
                from = point(entry, "from"),
                to = point(entry, "to"),
                at = point(entry, "at"),
                center = point(entry, "center"),
                points = pointList(entry, "points"),
                radius = doubleValue(entry, "radius"),
                startAngle = doubleValue(entry, "startAngle"),
                endAngle = doubleValue(entry, "endAngle"),
                vertex = point(entry, "vertex"),
                text = stringValue(entry, "text"),
                label = stringValue(entry, "label"),
                style = stringValue(entry, "style"),
                filled = entry.get("filled")?.asBoolean() ?: false,
                radiusY = doubleValue(entry, "radiusY"),
                control1 = point(entry, "control1"),
                control2 = point(entry, "control2"),
            )
        }
        return out
    }

    private fun nodes(node: JsonNode): List<FigureNode>? {
        val array = array(node, "nodes") ?: return null
        val out = ArrayList<FigureNode>(array.size())
        for (index in 0 until array.size()) {
            val entry = array.get(index)
            out += FigureNode(
                id = stringValue(entry, "id").orEmpty(),
                label = stringValue(entry, "label").orEmpty(),
                parent = stringValue(entry, "parent"),
            )
        }
        return out
    }

    private fun sets(node: JsonNode): List<FigureSet>? {
        val array = array(node, "sets") ?: return null
        val out = ArrayList<FigureSet>(array.size())
        for (index in 0 until array.size()) {
            val entry = array.get(index)
            out += FigureSet(label = stringValue(entry, "label"), items = stringList(entry, "items").orEmpty())
        }
        return out
    }

    private fun array(node: JsonNode, field: String): JsonNode? {
        val value = node.get(field) ?: return null
        if (value.isNull || !value.isArray || value.size() == 0) return null
        return value
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

    private fun point(node: JsonNode, field: String): List<Double>? {
        val value = node.get(field) ?: return null
        if (!value.isArray || value.size() != 2) return null
        return listOf(value.get(0).asDouble(), value.get(1).asDouble())
    }

    private fun pointList(node: JsonNode, field: String): List<List<Double>>? {
        val value = node.get(field) ?: return null
        if (!value.isArray) return null
        val out = ArrayList<List<Double>>(value.size())
        for (index in 0 until value.size()) {
            val pair = value.get(index)
            if (!pair.isArray || pair.size() != 2) continue
            out += listOf(pair.get(0).asDouble(), pair.get(1).asDouble())
        }
        return out
    }

    private fun hasPoint(node: JsonNode, field: String): Boolean = point(node, field) != null

    private fun stringValue(node: JsonNode, field: String): String? =
        node.get(field)?.takeIf { it.isString }?.asString()?.takeIf { it.isNotBlank() }

    private fun doubleValue(node: JsonNode, field: String): Double? =
        node.get(field)?.takeIf { it.isNumber }?.asDouble()

    private fun violation(code: String, message: String) =
        SchemaViolation(FindingSeverity.BLOCKER, code, message)
}
