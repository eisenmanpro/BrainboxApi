package com.afrithecus.brainbox.api.media

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * A scanner sidecar reached over HTTP: the raw object is POSTed as
 * `application/octet-stream` and the service answers
 *
 *     { "status": "CLEAN" | "INFECTED" | "ERROR", "detail": "..." }
 *
 * Any other response — a non-2xx, an unparseable body, a timeout — is an
 * [MediaScanStatus.ERROR], never a clean verdict, so a broken scanner cannot silently
 * wave an object through. Use this provider for a cloud scanning API (Google Safe
 * Browsing / VirusTotal-style URL checks, a vendor malware API) or a small internal
 * sidecar that wraps ClamAV.
 */
@Component
class HttpMediaScanner(
    private val properties: MediaProperties,
    private val mapper: ObjectMapper,
) : MediaScanner {

    override val name: String = "http"

    private val log = LoggerFactory.getLogger(javaClass)

    private val client: HttpClient by lazy {
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(properties.scan.timeoutMillis))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build()
    }

    override fun scan(bytes: ByteArray, contentType: String?): MediaScanResult {
        val url = properties.scan.http.url.trim()
        if (url.isEmpty()) {
            return MediaScanResult(MediaScanStatus.ERROR, "No scanner URL is configured")
        }
        return try {
            val builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMillis(properties.scan.timeoutMillis))
                .header("Content-Type", "application/octet-stream")
                .POST(HttpRequest.BodyPublishers.ofByteArray(bytes))
            properties.scan.http.bearerToken.trim().takeIf { it.isNotEmpty() }
                ?.let { builder.header("Authorization", "Bearer " + it) }
            val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() !in 200..299) {
                MediaScanResult(MediaScanStatus.ERROR, "Scanner answered HTTP " + response.statusCode())
            } else {
                verdictOf(response.body())
            }
        } catch (failure: Exception) {
            log.warn("HTTP scan failed: {}", failure.message)
            MediaScanResult(MediaScanStatus.ERROR, "The malware scanner is unavailable")
        }
    }

    private fun verdictOf(body: String): MediaScanResult {
        val node = runCatching { mapper.readTree(body) }.getOrNull()
            ?: return MediaScanResult(MediaScanStatus.ERROR, "The malware scanner returned an unreadable verdict")
        val detail = node.get("detail")?.asString()?.takeIf { it.isNotBlank() }
        return when (node.get("status")?.asString()?.trim()?.uppercase()) {
            "CLEAN" -> MediaScanResult(MediaScanStatus.CLEAN)
            "INFECTED" -> MediaScanResult(MediaScanStatus.INFECTED, detail ?: "malware signature match")
            "ERROR" -> MediaScanResult(MediaScanStatus.ERROR, detail ?: "The malware scanner reported an error")
            // An unknown status is not an approval.
            else -> MediaScanResult(MediaScanStatus.ERROR, "The malware scanner returned an unknown status")
        }
    }
}
