package com.afrithecus.brainbox.api.content.mcp

import com.afrithecus.brainbox.api.common.error.ApiErrorCode
import com.afrithecus.brainbox.api.common.error.ApiException
import com.afrithecus.brainbox.api.common.error.invalidArgument
import com.afrithecus.brainbox.api.common.net.PublicUrlPolicy
import com.afrithecus.brainbox.api.content.AppContentProperties
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration

/**
 * web_fetch: fetches one allow-listed public page as plain text, for grounding only
 * (docs/PHASE7_AGENT_ARCHITECTURE.md 2.9). It never follows redirects, refuses a
 * private or metadata host, caps the body and returns the URL plus a content hash so
 * provenance is auditable; scraped text is grounding and must never be reproduced
 * verbatim in learner content. Off by default and deny-all until an allow-list is set.
 */
@Component
class WebFetchTool(
    private val properties: AppContentProperties,
    private val mapper: ObjectMapper,
    private val clock: Clock,
) : McpTool {

    override val name: String = "web_fetch"

    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofMillis(properties.tools.webFetch.timeoutMs.coerceIn(500L, 30_000L)))
        .build()

    override fun invoke(payload: JsonNode): JsonNode {
        val config = properties.tools.webFetch
        if (!config.enabled) {
            throw ApiException(ApiErrorCode.SERVICE_UNAVAILABLE, "web fetch is disabled")
        }
        val url = payload.get("url")?.asString()?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw invalidArgument("web_fetch requires 'url'")
        if (!PublicUrlPolicy.isAllowed(url)) {
            throw invalidArgument("url must be a public http(s) URL")
        }
        val uri = URI(url)
        val host = PublicUrlPolicy.normalizeHost(uri.host) ?: throw invalidArgument("url has no host")
        if (config.blockedDomains.any { matchesHost(host, it) }) {
            throw ApiException(ApiErrorCode.FORBIDDEN, "domain is restricted: " + host)
        }
        if (config.allowedDomains.isEmpty() || config.allowedDomains.none { matchesHost(host, it) }) {
            throw ApiException(ApiErrorCode.FORBIDDEN, "domain is not allow-listed: " + host)
        }
        if (!PublicUrlPolicy.isPublicHost(host)) {
            throw invalidArgument("url does not resolve to a public host")
        }
        val timeout = Duration.ofMillis(config.timeoutMs.coerceIn(500L, 30_000L))
        val request = HttpRequest.newBuilder(uri)
            .timeout(timeout)
            .header("User-Agent", "BrainboxContentBot/1.0 (+grounding)")
            .GET()
            .build()
        val response = try {
            client.send(request, HttpResponse.BodyHandlers.ofByteArray())
        } catch (failure: Exception) {
            throw ApiException(ApiErrorCode.SERVICE_UNAVAILABLE, "web fetch failed: " + failure.message)
        }
        if (response.statusCode() !in 200..299) {
            throw ApiException(ApiErrorCode.SERVICE_UNAVAILABLE, "web fetch returned " + response.statusCode())
        }
        val body = response.body()
        val truncated = body.size > config.maxBytes
        val kept = if (truncated) body.copyOfRange(0, config.maxBytes) else body
        val node = mapper.createObjectNode()
        node.put("url", uri.toString())
        node.put("host", host)
        node.put("text", toPlainText(String(kept, StandardCharsets.UTF_8)))
        node.put("truncated", truncated)
        node.put("fetchedAt", clock.instant().toEpochMilli())
        node.put("contentSha256", sha256Hex(kept))
        return node
    }

    private fun matchesHost(host: String, domain: String): Boolean {
        val normalized = domain.trim().lowercase().removePrefix(".")
        if (normalized.isEmpty()) return false
        val candidate = host.lowercase()
        return candidate == normalized || candidate.endsWith("." + normalized)
    }

    private fun toPlainText(html: String): String = html
        .replace(SCRIPT_OR_STYLE, " ")
        .replace(TAG, " ")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace(WHITESPACE, " ")
        .trim()

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val out = StringBuilder(digest.size * 2)
        for (byte in digest) {
            val code = byte.toInt() and 0xFF
            out.append(HEX[code shr 4]).append(HEX[code and 0x0F])
        }
        return out.toString()
    }

    private companion object {
        val SCRIPT_OR_STYLE = Regex("(?is)<(script|style)[^>]*>.*?</\\1>")
        val TAG = Regex("(?s)<[^>]+>")
        val WHITESPACE = Regex("\\s+")
        const val HEX = "0123456789abcdef"
    }
}
