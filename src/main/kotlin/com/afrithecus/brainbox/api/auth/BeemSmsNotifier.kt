package com.afrithecus.brainbox.api.auth

import com.afrithecus.brainbox.api.identity.entity.UserEntity
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * Beem Africa SMS delivery (pilot/fallback provider, docs/phone_verification.md).
 *
 * JSON POST with HTTP Basic (apiKey:secret). Beem expects the destination in international digits
 * (2547...) without a leading plus, and requires a registered source_addr. Single attempt only,
 * for the same duplicate-code reason as the Africa's Talking sender.
 */
@Component
@ConditionalOnProperty(
    prefix = "app.auth.phone-verification",
    name = ["provider"],
    havingValue = "beem",
)
class BeemSmsNotifier(
    private val properties: PhoneVerificationProperties,
    private val mapper: ObjectMapper,
) : PhoneVerificationNotifier {

    private val client = HttpClient.newBuilder().connectTimeout(properties.connectTimeout).build()

    override fun send(user: UserEntity, code: String) {
        val config = properties.beem
        if (!config.configured) {
            throw IllegalStateException("Beem credentials are not configured")
        }
        if (config.senderId.isBlank()) {
            throw IllegalStateException("Beem sender id is not configured")
        }
        val to = PhoneNumbers.toInternationalDigits(user.phoneNumber.orEmpty())
            ?: throw IllegalStateException("Account has no valid phone number to verify")
        val body = mapper.writeValueAsString(
            linkedMapOf(
                "source_addr" to config.senderId,
                "encoding" to 0,
                "message" to phoneVerificationMessage(properties, code),
                "recipients" to listOf(
                    linkedMapOf("recipient_id" to 1, "dest_addr" to to),
                ),
            ),
        )
        val credentials = Base64.getEncoder()
            .encodeToString((config.apiKey + ":" + config.secret).toByteArray(StandardCharsets.UTF_8))
        val request = HttpRequest.newBuilder(URI(config.baseUrl))
            .timeout(properties.requestTimeout)
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
    }
}
