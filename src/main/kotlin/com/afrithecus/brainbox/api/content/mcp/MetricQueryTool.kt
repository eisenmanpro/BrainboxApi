package com.afrithecus.brainbox.api.content.mcp

import com.afrithecus.brainbox.api.common.error.invalidArgument
import com.afrithecus.brainbox.api.content.ContentQueueAdminService
import com.afrithecus.brainbox.api.ops.OpsCoverageService
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode

/**
 * metric_query: DB-backed content and operations metrics for the agent loop, so a
 * supervisor can decide what to generate next without a bespoke query. Named
 * metrics: coverage (shelf coverage per subject x grade) and queue (generation
 * queue depth, source policy and budget).
 */
@Component
class MetricQueryTool(
    private val coverage: OpsCoverageService,
    private val queue: ContentQueueAdminService,
    private val mapper: ObjectMapper,
) : McpTool {

    override val name: String = "metric_query"

    override fun invoke(payload: JsonNode): JsonNode {
        val metric = payload.get("name")?.asString()?.trim()?.lowercase()
            ?: throw invalidArgument("metric_query requires 'name'")
        return when (metric) {
            "coverage" -> toNode(coverage.coverage())
            "queue" -> toNode(queue.summary())
            else -> throw invalidArgument("unknown metric '" + metric + "'; expected coverage or queue")
        }
    }

    private fun toNode(value: Any): ObjectNode =
        mapper.readTree(mapper.writeValueAsString(value)) as ObjectNode
}
