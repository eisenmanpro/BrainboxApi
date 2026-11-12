package com.afrithecus.brainbox.api.sms

import com.afrithecus.brainbox.api.auth.PhoneNumbers
import com.afrithecus.brainbox.api.auth.PhoneVerificationProperties
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.Base64

/** Beem Africa SMS API (pilot/fallback provider, docs/phone_verification.md). */
class BeemSmsGateway(
    private val config: PhoneVerificationProperties.Beem,
    connectTimeout: Duration,
    private val requestTimeout: Duration,
    private val mapper: ObjectMapper,
) : SmsGateway {

    override val provider: String = "beem"

    private val client = HttpClient.newBuilder().connectTimeout(connectTimeout).build()

    override fun send(to: String, message: String): String? {
        if (!config.configured) throw IllegalStateException("Beem credentials are not configured")
        if (config.senderId.isBlank()) throw IllegalStateException("Beem sender id is not configured")
        val destination = PhoneNumbers.toInternationalDigits(to)
            ?: throw IllegalStateException("Phone number is not a valid Kenyan number")
        val body = mapper.writeValueAsString(
            linkedMapOf(
                "source_addr" to config.senderId,
                "encoding" to 0,
                "message" to message,
                "recipients" to listOf(
                    linkedMapOf("recipient_id" to 1, "dest_addr" to destination),
                ),
            ),
        )
        val credentials = Base64.getEncoder()
            .encodeToString((config.apiKey + ":" + config.secret).toByteArray(StandardCharsets.UTF_8))
        val request = HttpRequest.newBuilder(URI(config.baseUrl))
            .timeout(requestTimeout)
            .header("Authorization", "Basic " + credentials)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            throw IllegalStateException("Beem returned " + response.statusCode())
        }
        val node = runCatching { mapper.readTree(response.body()) }.getOrNull()
        if (node != null && node.has("successful") && !node.path("successful").asBoolean()) {
            throw IllegalStateException("Beem did not accept the message")
        }
        return node?.path("request_id")?.asString("")?.trim()?.takeIf { it.isNotEmpty() }
    }
}
