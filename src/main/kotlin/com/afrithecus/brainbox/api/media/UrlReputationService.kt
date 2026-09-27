package com.afrithecus.brainbox.api.media

import com.afrithecus.brainbox.api.common.net.PublicUrlPolicy
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** What a URL check concluded. */
enum class UrlReputationStatus { CLEAN, BLOCKED, SKIPPED, ERROR }

data class UrlReputationResult(
    val status: UrlReputationStatus,
    val detail: String? = null,
    val provider: String = "none",
) {
    val allowed: Boolean
        get() = status == UrlReputationStatus.CLEAN ||
            status == UrlReputationStatus.SKIPPED ||
            status == UrlReputationStatus.ERROR
}

/**
 * URL reputation for URLs the platform is about to store or fetch (generated figure images,
 * grounding sources, and an operator's on-demand check).
 *
 * Providers, all runtime-selectable from the console:
 *
 * - `none` — nothing is checked (`SKIPPED`, recorded).
 * - `deny_list` — the shared [PublicUrlPolicy] rules: scheme allow-list, no loopback,
 *   private, link-local or metadata hosts, no credentials in the URL. This catches the
 *   SSRF-shaped URLs without any external call, which is why it is the recommended
 *   provider for a deployment with no reputation service.
 * - `http` — a provider endpoint that answers
 *   `{"status":"CLEAN"|"MALICIOUS"|"ERROR","detail":"..."}`. Any other answer is an
 *   `ERROR`, never a clean verdict.
 *
 * A blocked URL is refused. An `ERROR` is refused only when
 * `app.media.url-reputation.fail-open=false` (the deployment/console choice); the outcome
 * is always recorded so the console shows what happened either way.
 */
@Service
class UrlReputationService(
    private val properties: MediaProperties,
    private val security: MediaSecuritySettings,
    private val mapper: ObjectMapper,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    private val client: HttpClient by lazy {
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(properties.urlReputation.timeoutMillis))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build()
    }

    /** True when the deployment/console has URL checking switched on. */
    fun enabled(): Boolean = security.urlReputationEnabled()

    /** The provider that would run, for the audit trail. */
    fun providerName(): String = if (!enabled()) "none" else security.urlReputationProvider()

    /**
     * Checks one URL. Never throws: an unparseable URL is `BLOCKED` (it cannot be served or
     * fetched), and a provider failure is `ERROR` so the caller can apply the fail-open
     * policy.
     */
    fun check(urlRaw: String?): UrlReputationResult {
        val url = urlRaw?.trim().orEmpty()
        if (url.isEmpty()) return UrlReputationResult(UrlReputationStatus.BLOCKED, "A URL is required", providerName())
        val provider = providerName()
        if (provider == "none") {
            return UrlReputationResult(UrlReputationStatus.SKIPPED, "URL reputation is not configured", "none")
        }
        return when (provider) {
            "deny_list" -> denyList(url)
            "http" -> httpCheck(url)
            else -> UrlReputationResult(UrlReputationStatus.ERROR, "Unknown URL provider '$provider'", provider)
        }
    }

    /** The outcome the caller should act on, applying the fail-open policy to `ERROR`. */
    fun enforce(url: String?): UrlReputationResult {
        val result = check(url)
        return if (result.status == UrlReputationStatus.ERROR && security.urlReputationFailOpen()) {
            result.copy(detail = (result.detail ?: "The URL check failed") + " (accepted: fail-open)")
        } else {
            result
        }
    }

    private fun denyList(url: String): UrlReputationResult {
        val uri = runCatching { URI(url) }.getOrNull()
            ?: return UrlReputationResult(UrlReputationStatus.BLOCKED, "The URL is not well formed", "deny_list")
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            return UrlReputationResult(UrlReputationStatus.BLOCKED, "Only http and https URLs are allowed", "deny_list")
        }
        if (!PublicUrlPolicy.isAllowed(url)) {
            return UrlReputationResult(
                UrlReputationStatus.BLOCKED,
                "The URL host is not a public address",
                "deny_list",
            )
        }
        return UrlReputationResult(UrlReputationStatus.CLEAN, null, "deny_list")
    }

    private fun httpCheck(url: String): UrlReputationResult {
        val endpoint = properties.urlReputation.http.url.trim()
        if (endpoint.isEmpty()) {
            return UrlReputationResult(UrlReputationStatus.ERROR, "No URL provider endpoint is configured", "http")
        }
        return try {
            val body = mapper.writeValueAsString(mapOf("url" to url))
            val builder = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(Duration.ofMillis(properties.urlReputation.timeoutMillis))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
            properties.urlReputation.http.bearerToken.trim().takeIf { it.isNotEmpty() }
                ?.let { builder.header("Authorization", "Bearer " + it) }
            val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() !in 200..299) {
                UrlReputationResult(UrlReputationStatus.ERROR, "URL provider answered HTTP " + response.statusCode(), "http")
            } else {
                verdictOf(response.body())
            }
        } catch (failure: Exception) {
            log.warn("URL reputation check failed: {}", failure.message)
            UrlReputationResult(UrlReputationStatus.ERROR, "The URL provider is unavailable", "http")
        }
    }

    private fun verdictOf(body: String): UrlReputationResult {
        val node = runCatching { mapper.readTree(body) }.getOrNull()
            ?: return UrlReputationResult(UrlReputationStatus.ERROR, "The URL provider returned an unreadable verdict", "http")
        val detail = node.get("detail")?.asString()?.takeIf { it.isNotBlank() }
        return when (node.get("status")?.asString()?.trim()?.uppercase()) {
            "CLEAN" -> UrlReputationResult(UrlReputationStatus.CLEAN, null, "http")
            "MALICIOUS", "BLOCKED" -> UrlReputationResult(
                UrlReputationStatus.BLOCKED,
                detail ?: "The URL is flagged as malicious",
                "http",
            )
            "ERROR" -> UrlReputationResult(UrlReputationStatus.ERROR, detail ?: "The URL provider reported an error", "http")
            else -> UrlReputationResult(UrlReputationStatus.ERROR, "The URL provider returned an unknown status", "http")
        }
    }
}
