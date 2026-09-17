package com.afrithecus.brainbox.api.storage

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
}
