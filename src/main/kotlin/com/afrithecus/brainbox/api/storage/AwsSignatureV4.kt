package com.afrithecus.brainbox.api.storage

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * AWS Signature Version 4 signing for the S3 API, implemented with the JDK only so
 * no AWS SDK (and no new dependency) is needed. It is verified against the worked
 * GET and PUT examples in the S3 "Signature Calculations for the Authorization
 * Header" documentation.
 *
 * Only the header-based, single-chunk form is implemented: the payload hash rides
 * in x-amz-content-sha256, which is what MinIO and S3 accept for ordinary uploads.
 * Presigned query-string URLs are a separate concern and are not built here.
 */
class AwsSignatureV4(
    private val accessKey: String,
    private val secretKey: String,
    private val region: String,
    private val service: String = "s3",
) {

    fun amzDate(timestamp: Instant): String = AMZ_DATE.format(timestamp)

    /**
     * The Authorization header for one request. [headers] must contain every header
     * to sign; x-amz-date is added from [timestamp] so it can never disagree with the
     * string to sign. Header names are lowercased and sorted.
     */
    fun authorization(
        method: String,
        path: String,
        queryParams: Map<String, String>,
        headers: Map<String, String>,
        payloadHash: String,
        timestamp: Instant,
    ): String {
        val amzDate = amzDate(timestamp)
        val dateStamp = amzDate.substring(0, 8)
        val toSign = (headers + ("x-amz-date" to amzDate))
            .entries
            .associate { it.key.lowercase() to normalizeHeaderValue(it.value) }
            .toSortedMap()
        val signedHeaders = toSign.keys.joinToString(";")
        val canonicalHeaders = toSign.entries.joinToString("") { (name, value) -> name + ":" + value + "\n" }

        val canonicalRequest = method.uppercase() + "\n" +
            encodePath(path) + "\n" +
            canonicalQuery(queryParams) + "\n" +
            canonicalHeaders + "\n" +
            signedHeaders + "\n" +
            payloadHash

        val scope = dateStamp + "/" + region + "/" + service + "/aws4_request"
        val stringToSign = "AWS4-HMAC-SHA256\n" + amzDate + "\n" + scope + "\n" + sha256Hex(canonicalRequest)
        val signature = hex(hmac(signingKey(dateStamp), stringToSign))
        return "AWS4-HMAC-SHA256 Credential=" + accessKey + "/" + scope +
            ", SignedHeaders=" + signedHeaders + ", Signature=" + signature
    }

    fun signingKey(dateStamp: String): ByteArray {
        val kDate = hmac(("AWS4" + secretKey).toByteArray(StandardCharsets.UTF_8), dateStamp)
        val kRegion = hmac(kDate, region)
        val kService = hmac(kRegion, service)
        return hmac(kService, "aws4_request")
    }

    private fun canonicalQuery(queryParams: Map<String, String>): String =
        queryParams.entries
            .map { uriEncode(it.key) + "=" + uriEncode(it.value) }
            .sorted()
            .joinToString("&")

    private fun normalizeHeaderValue(value: String): String = value.trim().replace(WHITESPACE, " ")

    private fun encodePath(path: String): String {
        val normalized = if (path.startsWith("/")) path else "/" + path
        return normalized.split("/").joinToString("/") { uriEncode(it) }
    }

    private fun uriEncode(value: String): String {
        val out = StringBuilder()
        for (byte in value.toByteArray(StandardCharsets.UTF_8)) {
            val code = byte.toInt() and 0xFF
            if (code.toChar() in UNRESERVED) {
                out.append(code.toChar())
            } else {
                out.append('%').append(HEX_UPPER[code shr 4]).append(HEX_UPPER[code and 0x0F])
            }
        }
        return out.toString()
    }

    private fun hmac(key: ByteArray, data: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data.toByteArray(StandardCharsets.UTF_8))
    }

    companion object {
        const val EMPTY_SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

        fun sha256Hex(value: String): String = sha256Hex(value.toByteArray(StandardCharsets.UTF_8))

        fun sha256Hex(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))

        fun hex(bytes: ByteArray): String {
            val out = StringBuilder(bytes.size * 2)
            for (byte in bytes) {
                val code = byte.toInt() and 0xFF
                out.append(HEX_LOWER[code shr 4]).append(HEX_LOWER[code and 0x0F])
            }
            return out.toString()
        }

        private const val HEX_LOWER = "0123456789abcdef"
        private const val HEX_UPPER = "0123456789ABCDEF"
        private val AMZ_DATE: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)
        private val UNRESERVED = (('A'..'Z') + ('a'..'z') + ('0'..'9') + listOf('-', '_', '.', '~')).toSet()
        private val WHITESPACE = Regex("\\s+")
    }
}
