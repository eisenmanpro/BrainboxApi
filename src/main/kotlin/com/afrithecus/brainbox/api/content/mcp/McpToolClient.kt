package com.afrithecus.brainbox.api.content.mcp

import com.afrithecus.brainbox.api.common.error.ApiErrorCode
import com.afrithecus.brainbox.api.common.error.ApiException
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode

/** Dispatches an MCP tool call to the registered [McpTool] with the given name. */
@Component
class McpToolClient(tools: List<McpTool>) {

    private val byName: Map<String, McpTool> = tools.associateBy { it.name }

    fun names(): Set<String> = byName.keys

    fun invoke(name: String, payload: JsonNode): JsonNode =
        (byName[name] ?: throw ApiException(ApiErrorCode.NOT_FOUND, "unknown MCP tool: " + name)).invoke(payload)
}
