package com.afrithecus.brainbox.api.storage

import java.time.Duration

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
}
