package com.afrithecus.brainbox.api.push

import com.afrithecus.brainbox.api.common.resilience.Retry
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Instant
import java.util.Base64

/**
 * FCM HTTP v1 sender. Active only when `app.push.fcm.enabled=true` and a service
 * account is configured; without credentials the [LoggingPushSender] is used
 * instead, so the notification path never depends on FCM being reachable.
 */
@Component
@ConditionalOnProperty(name = ["app.push.fcm.enabled"], havingValue = "true")
class FcmPushSender(
    private val properties: PushProperties,
    private val mapper: ObjectMapper,
) : PushSender {

    private val log = LoggerFactory.getLogger(javaClass)
    private val client = HttpClient.newBuilder().connectTimeout(properties.connectTimeout).build()

    @Volatile private var cachedToken: String? = null
    @Volatile private var cachedExpiry: Instant = Instant.MIN
    @Volatile private var cachedAccount: ServiceAccount? = null

    override fun send(tokens: List<String>, message: PushMessage): List<String> {
        if (tokens.isEmpty()) return emptyList()
        val account = runCatching { serviceAccount() }.getOrElse {
            log.warn("FCM service account is unavailable: {}", it.message)
            return emptyList()
        }
        val accessToken = runCatching { Retry.withBackoff { accessToken(account) } }.getOrElse {
            log.warn("FCM access token exchange failed: {}", it.message)
            return emptyList()
        }
        val url = "https://fcm.googleapis.com/v1/projects/" + account.projectId + "/messages:send"
        val invalid = mutableListOf<String>()
        tokens.forEach { token ->
            if (deliver(url, accessToken, token, message) == Outcome.INVALID) invalid += token
        }
        return invalid
    }

    private fun deliver(url: String, accessToken: String, token: String, message: PushMessage): Outcome {
        val body = mapper.writeValueAsString(FcmPayloads.requestBody(token, message, properties.channelId))
        val request = HttpRequest.newBuilder(URI(url))
            .timeout(properties.requestTimeout)
            .header("Authorization", "Bearer " + accessToken)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build()
        // Retry transient failures (429/5xx, socket errors); other 4xx are terminal.
        val response = runCatching {
            Retry.withBackoff(attempts = SEND_ATTEMPTS) {
                val candidate = client.send(request, HttpResponse.BodyHandlers.ofString())
                if (candidate.statusCode() == 429 || candidate.statusCode() in 500..599) {
                    throw TransientFcmException("FCM returned " + candidate.statusCode())
                }
                candidate
            }
        }.getOrElse { error ->
            log.warn("FCM send failed: {}", error.message)
            return Outcome.FAILED
        }
        return when {
            response.statusCode() in 200..299 -> Outcome.SENT
            response.statusCode() == 404 || response.body().contains("UNREGISTERED") ||
                response.body().contains("INVALID_ARGUMENT") -> Outcome.INVALID
            else -> {
                log.warn("FCM send rejected with {}: {}", response.statusCode(), response.body())
                Outcome.FAILED
            }
        }
    }

    /** Thrown for a 429/5xx so [Retry] backs off and tries again. */
    private class TransientFcmException(message: String) : java.io.IOException(message)

    private fun serviceAccount(): ServiceAccount {
        cachedAccount?.let { return it }
        val path = properties.serviceAccountFile.trim()
        if (path.isEmpty()) throw IllegalStateException("app.push.fcm.service-account-file is not set")
        val node = mapper.readTree(Files.readAllBytes(Paths.get(path)))
        val account = ServiceAccount(
            projectId = node.get("project_id")?.asString()?.takeIf { it.isNotBlank() } ?: properties.projectId,
            clientEmail = node.get("client_email")?.asString()
                ?: throw IllegalStateException("service account is missing client_email"),
            privateKey = privateKey(node.get("private_key")?.asString()
                ?: throw IllegalStateException("service account is missing private_key")),
            tokenUri = node.get("token_uri")?.asString()?.takeIf { it.isNotBlank() } ?: properties.tokenUri,
        )
        if (account.projectId.isBlank()) throw IllegalStateException("service account is missing project_id")
        cachedAccount = account
        return account
    }

    private fun accessToken(account: ServiceAccount): String {
        cachedToken?.let { if (cachedExpiry.isAfter(Instant.now().plusSeconds(60))) return it }
        val assertion = assertion(account)
        val form = "grant_type=" + URLEncoder.encode(JWT_BEARER, StandardCharsets.UTF_8) +
            "&assertion=" + URLEncoder.encode(assertion, StandardCharsets.UTF_8)
        val request = HttpRequest.newBuilder(URI(account.tokenUri))
            .timeout(properties.requestTimeout)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8))
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) throw IllegalStateException("token endpoint returned " + response.statusCode())
        val node = mapper.readTree(response.body())
        val access = node.get("access_token")?.asString() ?: throw IllegalStateException("token response has no access_token")
        val expiresIn = node.get("expires_in")?.asLong() ?: 3600L
        cachedToken = access
        cachedExpiry = Instant.now().plusSeconds(expiresIn)
        return access
    }

    private fun assertion(account: ServiceAccount): String {
        val now = Instant.now().epochSecond
        val header = base64Url(mapper.writeValueAsBytes(mapOf("alg" to "RS256", "typ" to "JWT")))
        val claims = base64Url(mapper.writeValueAsBytes(linkedMapOf(
            "iss" to account.clientEmail,
            "scope" to FCM_SCOPE,
            "aud" to account.tokenUri,
            "iat" to now,
            "exp" to now + 3600,
        )))
        val signingInput = header + "." + claims
        val signer = Signature.getInstance("SHA256withRSA")
        signer.initSign(account.privateKey)
        signer.update(signingInput.toByteArray(StandardCharsets.UTF_8))
        return signingInput + "." + base64Url(signer.sign())
    }

    private fun privateKey(pem: String): PrivateKey {
        val cleaned = pem
            .replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replace(Regex("\\s"), "")
        return KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(cleaned)))
    }

    private fun base64Url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private data class ServiceAccount(
        val projectId: String,
        val clientEmail: String,
        val privateKey: PrivateKey,
        val tokenUri: String,
    )

    private enum class Outcome { SENT, INVALID, FAILED }

    private companion object {
        const val SEND_ATTEMPTS = 3
        const val FCM_SCOPE = "https://www.googleapis.com/auth/firebase.messaging"
        const val JWT_BEARER = "urn:ietf:params:oauth:grant-type:jwt-bearer"
    }
}
