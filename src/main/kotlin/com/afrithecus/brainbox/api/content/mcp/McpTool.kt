package com.afrithecus.brainbox.api.content.mcp

import tools.jackson.databind.JsonNode

/**
 * One in-process MCP tool (Phase 7.2). Tools are plain Spring beans: adding a
 * tool to the context registers it with [McpToolClient]. No network MCP server
 * is started here; this is the seam later slices extend.
 */
interface McpTool {

    val name: String

    fun invoke(payload: JsonNode): JsonNode
}
