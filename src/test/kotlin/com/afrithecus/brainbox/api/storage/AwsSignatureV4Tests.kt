package com.afrithecus.brainbox.api.storage

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The SigV4 signer is checked against the worked GET and PUT examples in the S3
 * "Signature Calculations for the Authorization Header" documentation, so the
 * hand-rolled signing is proven correct without an AWS SDK.
 */
class AwsSignatureV4Tests {

    private val timestamp = Instant.parse("2013-05-24T00:00:00Z")

    private fun signer() = AwsSignatureV4(
        accessKey = "AKIAIOSFODNN7EXAMPLE",
        secretKey = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY",
        region = "us-east-1",
    )

    @Test
    fun matchesTheDocumentedGetObjectExample() {
        val auth = signer().authorization(
            method = "GET",
            path = "/test.txt",
            queryParams = emptyMap(),
            headers = mapOf(
                "host" to "examplebucket.s3.amazonaws.com",
                "range" to "bytes=0-9",
                "x-amz-content-sha256" to AwsSignatureV4.EMPTY_SHA256,
            ),
            payloadHash = AwsSignatureV4.EMPTY_SHA256,
            timestamp = timestamp,
        )
        assertTrue(
            auth.contains("SignedHeaders=host;range;x-amz-content-sha256;x-amz-date"),
            auth,
        )
        assertTrue(
            auth.endsWith("Signature=f0e8bdb87c964420e857bd35b5d6ed310bd44f0170aba48dd91039c6036bdb41"),
            auth,
        )
    }

    @Test
    fun matchesTheDocumentedPutObjectExample() {
        val body = "Welcome to Amazon S3."
        val bodyHash = AwsSignatureV4.sha256Hex(body)
        assertTrue(
            bodyHash == "44ce7dd67c959e0d3524ffac1771dfbba87d2b6b4b4e99e42034a8b803f8b072",
            bodyHash,
        )
        val auth = signer().authorization(
            method = "PUT",
            path = "/test\$file.text",
            queryParams = emptyMap(),
            headers = mapOf(
                "date" to "Fri, 24 May 2013 00:00:00 GMT",
                "host" to "examplebucket.s3.amazonaws.com",
                "x-amz-content-sha256" to bodyHash,
                "x-amz-storage-class" to "REDUCED_REDUNDANCY",
            ),
            payloadHash = bodyHash,
            timestamp = timestamp,
        )
        assertTrue(
            auth.contains("SignedHeaders=date;host;x-amz-content-sha256;x-amz-date;x-amz-storage-class"),
            auth,
        )
        assertTrue(
            auth.endsWith("Signature=98ad721746da40c64f1a55b78f14c238d841ea1380cd77a1b5971af0ece108bd"),
            auth,
        )
    }
}
