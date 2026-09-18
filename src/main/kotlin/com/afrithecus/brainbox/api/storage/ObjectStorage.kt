package com.afrithecus.brainbox.api.storage

import java.time.Duration

/** Object size and stored content type, as reported by a HEAD. */
data class ObjectMetadata(val sizeBytes: Long, val contentType: String?)

/** A signed direct-upload instruction: where to PUT and which headers to send. */
data class PresignedUpload(val url: String, val method: String, val headers: Map<String, String>)

/**
 * A small object store shared by media uploads and rendered report PDFs. Keys are
 * opaque, server-generated names (never user filenames), which is what keeps the
 * local and S3 backends interchangeable and the key space traversal-safe.
 */
interface ObjectStorage {

    fun put(key: String, bytes: ByteArray, contentType: String)

    /** Returns null when the object does not exist. */
    fun get(key: String): ByteArray?

    /** Repeat-safe. */
    fun delete(key: String)

    /**
     * A short-lived direct-download URL, or null when the backend streams bytes
     * itself (local disk). The stable API URL stays the contract; the caller may
     * redirect to this when it is non-null, keeping the bytes off the API node.
     */
    fun presignGet(key: String, ttl: Duration): String? = null

    /**
     * A short-lived direct-upload (PUT) URL, or null when the backend cannot
     * presign (local disk). The bytes then go straight to the store; the caller
     * must still verify the object before serving it.
     */
    fun presignPut(key: String, contentType: String, ttl: Duration): PresignedUpload? = null

    /** Object size/content type via HEAD, or null when it does not exist. */
    fun head(key: String): ObjectMetadata? = null

    /** The first [length] bytes from [offset], or null when absent. Cheap prefix read. */
    fun getRange(key: String, offset: Long, length: Int): ByteArray? = null
}
