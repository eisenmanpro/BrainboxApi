package com.afrithecus.brainbox.api.payments

import com.afrithecus.brainbox.api.common.error.ApiErrorCode
import com.afrithecus.brainbox.api.common.error.ApiException
import org.slf4j.LoggerFactory
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

/**
 * IntaSend collections client (doc 14 section 6). The wire format is fixed by
 * IntaSend's own SDK:
 *
 *  - STK push: POST {base}/payment/mpesa-stk-push/ with Bearer secret key and the
 *    INTASEND_PUBLIC_API_KEY header; body carries public_key, method M-PESA,
 *    amount, phone_number and api_ref.
 *  - Status:   POST {base}/payment/status/ with only the publishable key.
 *
 * Dependency-free (JDK HttpClient) so the runtime classpath stays Spring Boot
 * starters. The server relays every call; the app never holds the secret key.
 */
class IntaSendPaymentGateway(
    private val properties: AppPaymentProperties,
    private val mapper: ObjectMapper,
) : PaymentGateway {

    private val log = LoggerFactory.getLogger(javaClass)
    private val client: HttpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build()

    override val name: String = "INTASEND"

    override fun stkPush(command: StkPushCommand): StkPushOutcome {
        val body = linkedMapOf<String, Any?>(
            "public_key" to properties.intasend.publishableKey,
            "currency" to command.currency,
            "method" to "M-PESA",
            "amount" to command.amount,
            "phone_number" to command.phoneNumber,
            "api_ref" to command.reference,
            "narrative" to command.narrative,
            "name" to command.name,
            "email" to command.email,
        )
        if (properties.intasend.walletId.isNotBlank()) body["wallet_id"] = properties.intasend.walletId
        val node = mapper.readTree(post("payment/mpesa-stk-push/", body, authorized = true))
        val reference = node.get("invoice")?.get("invoice_id")?.asString()
            ?: node.get("invoice_id")?.asString()
        val state = node.get("invoice")?.get("state")?.asString()
            ?: node.get("state")?.asString()
            ?: PaymentStates.PENDING
        if (reference.isNullOrBlank()) {
            return StkPushOutcome.Rejected("IntaSend did not return an invoice reference")
        }
        return StkPushOutcome.Accepted(reference, state)
    }

    override fun status(providerReference: String): GatewayPaymentState {
        val body = linkedMapOf<String, Any?>(
            "invoice_id" to providerReference,
            "public_key" to properties.intasend.publishableKey,
        )
        val node = mapper.readTree(post("payment/status/", body, authorized = false))
        val state = node.get("invoice")?.get("state")?.asString()
            ?: node.get("state")?.asString()
            ?: PaymentStates.PENDING
        val reason = node.get("invoice")?.get("failed_reason")?.asString()
            ?: node.get("failed_reason")?.asString()
        return GatewayPaymentState(state, reason)
    }

    private fun post(path: String, body: Map<String, Any?>, authorized: Boolean): String {
        val publishable = properties.intasend.publishableKey
        if (publishable.isBlank()) {
            throw ApiException(ApiErrorCode.SERVICE_UNAVAILABLE, "IntaSend publishable key is not configured")
        }
        val request = HttpRequest.newBuilder(URI(properties.resolvedBaseUrl + "/" + path))
            .timeout(REQUEST_TIMEOUT)
            .header("Content-Type", "application/json")
            .header("INTASEND_PUBLIC_API_KEY", publishable)
            .also { builder ->
                if (authorized) {
                    if (properties.intasend.secretKey.isBlank()) {
                        throw ApiException(ApiErrorCode.SERVICE_UNAVAILABLE, "IntaSend secret key is not configured")
                    }
                    builder.header("Authorization", "Bearer " + properties.intasend.secretKey)
                }
            }
            .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body), StandardCharsets.UTF_8))
            .build()
        val response = try {
            client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        } catch (failure: Exception) {
            log.warn("IntaSend request {} failed: {}", path, failure.message)
            throw ApiException(ApiErrorCode.SERVICE_UNAVAILABLE, "IntaSend request failed: " + failure.message)
        }
        if (response.statusCode() !in 200..299) {
            log.warn("IntaSend {} returned {}: {}", path, response.statusCode(), response.body())
            throw ApiException(ApiErrorCode.SERVICE_UNAVAILABLE, "IntaSend returned " + response.statusCode())
        }
        return response.body()
    }

    private companion object {
        val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(5)
        val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(30)
    }
}
