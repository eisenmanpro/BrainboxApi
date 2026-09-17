package com.afrithecus.brainbox.api.content.mcp

import com.afrithecus.brainbox.api.common.error.invalidArgument
import com.afrithecus.brainbox.api.content.validation.ContentValidationService
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode
import java.util.UUID

/**
 * validate_content: runs the deterministic validator chain for one content row and
 * returns the structured ValidationReport. The agent loop calls this as a tool so
 * it acts on findings instead of re-implementing the rules.
 */
@Component
class ValidationTool(
    private val validation: ContentValidationService,
    private val mapper: ObjectMapper,
) : McpTool {

    override val name: String = "validate_content"

    override fun invoke(payload: JsonNode): JsonNode {
        val contentType = payload.get("contentType")?.asString()?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }
            ?: "UNIT"
        val contentId = payload.get("contentId")?.asString()?.trim()
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: throw invalidArgument("validate_content requires a UUID 'contentId'")
        val report = validation.validate(contentType, contentId)
        return mapper.readTree(mapper.writeValueAsString(report)) as ObjectNode
    }
}
