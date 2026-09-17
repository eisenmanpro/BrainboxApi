package com.afrithecus.brainbox.api.report

import com.afrithecus.brainbox.api.storage.ObjectStorage
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service

/**
 * Rendered report PDFs, stored through the shared ObjectStorage seam. Files are
 * keyed by an opaque storage name (the job id) so the object key never leaks into a
 * URL; the signed download endpoint resolves the job and reads its bytes. The
 * backend is app.storage.provider (LOCAL disk or S3-compatible MinIO).
 */
@Service
class ReportStorage(@Qualifier("reportObjectStorage") private val storage: ObjectStorage) {

    fun save(storageName: String, bytes: ByteArray): Long {
        storage.put(storageName, bytes, "application/pdf")
        return bytes.size.toLong()
    }

    fun read(storageName: String): ByteArray? = storage.get(storageName)

    fun delete(storageName: String) = storage.delete(storageName)
}
