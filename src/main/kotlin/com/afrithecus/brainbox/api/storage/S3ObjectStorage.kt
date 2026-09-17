package com.afrithecus.brainbox.api.storage

import com.afrithecus.brainbox.api.common.error.ApiErrorCode
import com.afrithecus.brainbox.api.common.error.ApiException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Clock
import java.time.Duration

/**
 * S3-compatible object storage (MinIO, or S3 itself) addressed with SigV4 and a
 * JDK HttpClient. Path-style addressing is the default because that is how MinIO
 * and most self-hosted gateways are reached; virtual-hosted style is available for
 * the public S3 endpoint. Bytes are proxied through the API node: the existing
 * multipart upload and signed-download contracts are unchanged.
 */
class S3ObjectStorage(
    private val config: StorageProperties.S3,
    private val keyPrefix: String,
    private val signer: AwsSignatureV4,
    private val clock: Clock,
) : ObjectStorage {

    private val client: HttpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build()

    init {
        require(config.endpoint.isNotBlank() && config.bucket.isNotBlank()) {
            "app.storage.s3.endpoint and app.storage.s3.bucket are required when app.storage.provider=s3"
        }
        require(config.accessKey.isNotBlank() && config.secretKey.isNotBlank()) {
            "app.storage.s3.access-key and app.storage.s3.secret-key are required when app.storage.provider=s3"
        }
    }

    override fun put(key: String, bytes: ByteArray, contentType: String) {
        val objectKey = objectKey(key)
        val payloadHash = AwsSignatureV4.sha256Hex(bytes)
        val request = request("PUT", objectKey, contentType, payloadHash)
            .method("PUT", HttpRequest.BodyPublishers.ofByteArray(bytes))
            .build()
        ensureSuccess(send(request), "PUT " + objectKey)
    }

    override fun get(key: String): ByteArray? {
        val objectKey = objectKey(key)
        val request = request("GET", objectKey, null, AwsSignatureV4.EMPTY_SHA256).GET().build()
        val response = send(request)
        if (response.statusCode() == 404) return null
        ensureSuccess(response, "GET " + objectKey)
        return response.body()
    }

    override fun delete(key: String) {
        val objectKey = objectKey(key)
        val request = request("DELETE", objectKey, null, AwsSignatureV4.EMPTY_SHA256).DELETE().build()
        val response = send(request)
        if (response.statusCode() == 404) return
        ensureSuccess(response, "DELETE " + objectKey)
    }

    override fun presignGet(key: String, ttl: Duration): String? {
        val url = urlFor(objectKey(key))
        return signer.presign(
            scheme = url.scheme,
            host = hostHeader(url),
            path = url.path,
            expiresSeconds = ttl.seconds.coerceIn(1L, MAX_PRESIGN_SECONDS),
            timestamp = clock.instant(),
        )
    }

    private fun request(method: String, objectKey: String, contentType: String?, payloadHash: String): HttpRequest.Builder {
        val url = urlFor(objectKey)
        val now = clock.instant()
        val headers = linkedMapOf(
            "host" to hostHeader(url),
            "x-amz-content-sha256" to payloadHash,
        )
        if (contentType != null) headers["content-type"] = contentType
        val authorization = signer.authorization(
            method = method,
            path = url.path,
            queryParams = emptyMap(),
            headers = headers,
            payloadHash = payloadHash,
            timestamp = now,
        )
        val builder = HttpRequest.newBuilder(url)
            .timeout(REQUEST_TIMEOUT)
            .header("Authorization", authorization)
            .header("x-amz-content-sha256", payloadHash)
            .header("x-amz-date", signer.amzDate(now))
        if (contentType != null) builder.header("Content-Type", contentType)
        return builder
    }

    private fun urlFor(objectKey: String): URI {
        val base = URI(config.endpoint.trim().trimEnd('/'))
        val scheme = base.scheme?.takeIf { it.isNotBlank() } ?: "http"
        val authority = if (base.port == -1) base.host else base.host + ":" + base.port
        val raw = if (config.pathStyle) {
            scheme + "://" + authority + "/" + config.bucket + "/" + objectKey
        } else {
            scheme + "://" + config.bucket + "." + authority + "/" + objectKey
        }
        return URI(raw)
    }

    private fun hostHeader(url: URI): String = if (url.port == -1) url.host else url.host + ":" + url.port

    private fun objectKey(key: String): String = if (keyPrefix.isBlank()) key else keyPrefix + "/" + key

    private fun send(request: HttpRequest): HttpResponse<ByteArray> =
        try {
            client.send(request, HttpResponse.BodyHandlers.ofByteArray())
        } catch (failure: Exception) {
            throw ApiException(ApiErrorCode.SERVICE_UNAVAILABLE, "object storage request failed: " + failure.message)
        }

    private fun ensureSuccess(response: HttpResponse<ByteArray>, operation: String) {
        if (response.statusCode() !in 200..299) {
            throw ApiException(ApiErrorCode.SERVICE_UNAVAILABLE, "object storage " + operation + " returned " + response.statusCode())
        }
    }

    private companion object {
        const val MAX_PRESIGN_SECONDS = 604800L
        val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(5)
        val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(30)
    }
}
