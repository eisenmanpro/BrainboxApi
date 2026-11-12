package com.afrithecus.brainbox.api.auth

import com.afrithecus.brainbox.api.identity.entity.UserEntity
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets

/**
 * Africa's Talking SMS delivery (primary provider, docs/phone_verification.md).
 *
 * The request is a form POST to the messaging endpoint with the account apiKey header. A single
 * attempt only: retrying an OTP send would deliver (and bill) a second code. A non-2xx response
 * or a recipient status other than Success is a delivery failure the service reports honestly.
 */
@Component
@ConditionalOnProperty(
    prefix = "app.auth.phone-verification",
    name = ["provider"],
    havingValue = "africastalking",
)
class AfricasTalkingSmsNotifier(
    private val properties: PhoneVerificationProperties,
    private val mapper: ObjectMapper,
) : PhoneVerificationNotifier {

    private val client = HttpClient.newBuilder().connectTimeout(properties.connectTimeout).build()

    override fun send(user: UserEntity, code: String) {
        val config = properties.africastalking
        if (!config.configured) {
            throw IllegalStateException("Africa's Talking credentials are not configured")
        }
        val to = PhoneNumbers.toE164(user.phoneNumber.orEmpty())
            ?: throw IllegalStateException("Account has no valid phone number to verify")
        val form = buildList {
            add("username=" + encode(config.username))
            add("to=" + encode(to))
            add("message=" + encode(phoneVerificationMessage(properties, code)))
            if (config.senderId.isNotBlank()) add("from=" + encode(config.senderId))
        }.joinToString("&")
        val request = HttpRequest.newBuilder(URI(config.baseUrl))
            .timeout(properties.requestTimeout)
            .header("apiKey", config.apiKey)
            .header("Accept", "application/json")
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8))
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            throw IllegalStateException("Africa's Talking returned " + response.statusCode())
        }
        val status = runCatching {
            mapper.readTree(response.body()).path("SMSMessageData").path("Recipients").path(0).path("status").asString()
        }.getOrNull()
        if (status != "Success") {
            throw IllegalStateException("Africa's Talking did not accept the message (status: " + status + ")")
        }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)
}
