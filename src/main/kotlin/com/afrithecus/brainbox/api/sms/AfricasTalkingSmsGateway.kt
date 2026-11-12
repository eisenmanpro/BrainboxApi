package com.afrithecus.brainbox.api.sms

import com.afrithecus.brainbox.api.auth.PhoneNumbers
import com.afrithecus.brainbox.api.auth.PhoneVerificationProperties
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

/** Africa's Talking messaging API (fallback provider, docs/phone_verification.md). */
class AfricasTalkingSmsGateway(
    private val config: PhoneVerificationProperties.AfricasTalking,
    connectTimeout: Duration,
    private val requestTimeout: Duration,
    private val mapper: ObjectMapper,
) : SmsGateway {

    override val provider: String = "africastalking"

    private val client = HttpClient.newBuilder().connectTimeout(connectTimeout).build()

    override fun send(to: String, message: String): String? {
        if (!config.configured) throw IllegalStateException("Africa's Talking credentials are not configured")
        val destination = PhoneNumbers.toE164(to)
            ?: throw IllegalStateException("Phone number is not a valid Kenyan number")
        val form = buildList {
            add("username=" + encode(config.username))
            add("to=" + encode(destination))
            add("message=" + encode(message))
            if (config.senderId.isNotBlank()) add("from=" + encode(config.senderId))
        }.joinToString("&")
        val request = HttpRequest.newBuilder(URI(config.baseUrl))
            .timeout(requestTimeout)
            .header("apiKey", config.apiKey)
            .header("Accept", "application/json")
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8))
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            throw IllegalStateException("Africa's Talking returned " + response.statusCode())
        }
        val recipient = runCatching {
            mapper.readTree(response.body()).path("SMSMessageData").path("Recipients").path(0)
        }.getOrNull()
        val status = recipient?.path("status")?.asString()
        if (status != "Success") {
            throw IllegalStateException("Africa's Talking did not accept the message (status: " + status + ")")
        }
        return recipient.path("messageId").asString("").trim().takeIf { it.isNotEmpty() }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)
}
