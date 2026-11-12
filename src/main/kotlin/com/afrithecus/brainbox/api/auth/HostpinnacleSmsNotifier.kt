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
 * HostPinnacle SMS delivery (default provider, docs/phone_verification.md).
 *
 * The request is a form POST to the portal's /send endpoint with the account API key header and
 * the portal username/password in the body (the API's documented shape). The response is HTTP 200
 * even for a rejected message, so the body's "status" field decides success: the official client
 * fakes success as {"status":"success"}, while real rejections look like
 * {"status":"error","statusCode":"162","reason":"Empty senderId"} or statusCode 203 for an
 * unapproved sender ID. A single attempt only: an OTP resend would deliver (and bill) a second code.
 */
@Component
@ConditionalOnProperty(
    prefix = "app.auth.phone-verification",
    name = ["provider"],
    havingValue = "hostpinnacle",
)
class HostpinnacleSmsNotifier(
    private val properties: PhoneVerificationProperties,
    private val mapper: ObjectMapper,
) : PhoneVerificationNotifier {

    private val client = HttpClient.newBuilder().connectTimeout(properties.connectTimeout).build()

    override fun send(user: UserEntity, code: String) {
        val config = properties.hostpinnacle
        if (!config.configured) {
            throw IllegalStateException("HostPinnacle credentials are not configured")
        }
        if (config.senderId.isBlank()) {
            throw IllegalStateException("HostPinnacle sender id is not configured")
        }
        val to = PhoneNumbers.toInternationalDigits(user.phoneNumber.orEmpty())
            ?: throw IllegalStateException("Account has no valid phone number to verify")
        val form = buildList {
            add("userid=" + encode(config.username))
            add("password=" + encode(config.password))
            add("msg=" + encode(phoneVerificationMessage(properties, code)))
            add("sendMethod=quick")
            add("senderid=" + encode(config.senderId))
            add("msgType=text")
            add("duplicatecheck=true")
            add("output=json")
            add("mobile=" + encode(to))
        }.joinToString("&")
        val request = HttpRequest.newBuilder(URI(config.baseUrl.trimEnd('/') + "/send"))
            .timeout(properties.requestTimeout)
            .header("apikey", config.apiKey)
            .header("cache-control", "no-cache")
            .header("Accept", "application/json")
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8))
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            throw IllegalStateException("HostPinnacle returned " + response.statusCode())
        }
        val node = runCatching { mapper.readTree(response.body()) }.getOrNull()
            ?: throw IllegalStateException("HostPinnacle returned an unrecognised response")
        val status = node.path("status").asString("").trim().lowercase()
        if (status != "success") {
            val reason = node.path("reason").asString("").trim()
            val code = node.path("statusCode").asString("").trim()
            val detail = listOf(reason, if (code.isNotBlank()) "statusCode " + code else "").filter { it.isNotBlank() }
            throw IllegalStateException(
                "HostPinnacle did not accept the message" +
                    if (detail.isEmpty()) " (status: " + node.path("status").asString("unknown") + ")" else " (" + detail.joinToString("; ") + ")",
            )
        }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)
}
