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

/**
 * HostPinnacle form POST to /send (default provider, docs/phone_verification.md).
 *
 * HTTP 200 is returned even for rejected messages, so the body's "status" field decides
 * success; an unrecognised body fails closed. One attempt only: callers decide whether a
 * retry is safe (for an OTP it is not).
 */
class HostpinnacleSmsGateway(
    private val config: PhoneVerificationProperties.Hostpinnacle,
    connectTimeout: Duration,
    private val requestTimeout: Duration,
    private val mapper: ObjectMapper,
) : SmsGateway {

    override val provider: String = "hostpinnacle"

    private val client = HttpClient.newBuilder().connectTimeout(connectTimeout).build()

    override fun send(to: String, message: String): String? {
        if (!config.configured) throw IllegalStateException("HostPinnacle credentials are not configured")
        if (config.senderId.isBlank()) throw IllegalStateException("HostPinnacle sender id is not configured")
        val destination = PhoneNumbers.toInternationalDigits(to)
            ?: throw IllegalStateException("Phone number is not a valid Kenyan number")
        val form = buildList {
            add("userid=" + encode(config.username))
            add("password=" + encode(config.password))
            add("msg=" + encode(message))
            add("sendMethod=quick")
            add("senderid=" + encode(config.senderId))
            add("msgType=text")
            add("duplicatecheck=true")
            add("output=json")
            add("mobile=" + encode(destination))
        }.joinToString("&")
        val request = HttpRequest.newBuilder(URI(config.baseUrl.trimEnd('/') + "/send"))
            .timeout(requestTimeout)
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
                    if (detail.isEmpty()) {
                        " (status: " + node.path("status").asString("unknown") + ")"
                    } else {
                        " (" + detail.joinToString("; ") + ")"
                    },
            )
        }
        return node.path("messageId").asString("").trim().takeIf { it.isNotEmpty() }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)
}
