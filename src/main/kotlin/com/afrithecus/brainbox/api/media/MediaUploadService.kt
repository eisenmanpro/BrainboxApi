package com.afrithecus.brainbox.api.media

import com.afrithecus.brainbox.api.common.error.ApiErrorCode
import com.afrithecus.brainbox.api.common.error.ApiException
import com.afrithecus.brainbox.api.common.error.invalidArgument
import com.afrithecus.brainbox.api.common.error.notFound
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.model.Role
import com.afrithecus.brainbox.api.media.entity.MediaUploadEntity
import com.afrithecus.brainbox.api.media.repository.MediaUploadRepository
import com.afrithecus.brainbox.api.media.web.MediaUploadInitiateRequest
import com.afrithecus.brainbox.api.media.web.MediaUploadTicket
import com.afrithecus.brainbox.api.storage.ObjectStorage
import com.afrithecus.brainbox.api.storage.StorageProperties
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.util.UUID

/**
 * Presigned client-direct upload (Phase 6). The API issues a short-lived PUT URL
 * so the bytes go straight to the object store; the API then verifies the object
 * before it can be served, because a presigned PUT bypasses the format detection
 * the proxied multipart path performs. A PENDING ticket is therefore required:
 *
 *  - initiate allocates a server-chosen key and records who owns it;
 *  - the client PUTs the bytes directly to the store;
 *  - confirm HEADs the size and reads a prefix to detect the real format, then
 *    requires it to match the declared type, or deletes the object and rejects.
 *
 * Tickets expire with the presigned URL, and a sweeper removes the object of any
 * ticket the client never confirmed.
 */
@Service
class MediaUploadService(
    @Qualifier("mediaObjectStorage") private val storage: ObjectStorage,
    private val uploads: MediaUploadRepository,
    private val storageProperties: StorageProperties,
    private val clock: Clock,
    @Value("\${app.media.max-upload-bytes:26214400}") private val maxUploadBytes: Long,
) {

    @Transactional
    fun initiate(owner: UserEntity, request: MediaUploadInitiateRequest): MediaUploadTicket {
        val purpose = parsePurpose(request.purpose)
        requirePurpose(owner, purpose)
        val kind = MediaContentTypes.forContentType(request.contentType)
            ?: request.fileName?.let(MediaContentTypes::forExtension)
            ?: throw invalidArgument("Unsupported file type")
        if (!MediaPolicies.allows(purpose, kind)) {
            throw invalidArgument("Unsupported file type for this upload")
        }
        if (uploads.countByOwnerIdAndStatus(owner.id, STATUS_PENDING) >= MAX_PENDING_PER_USER) {
            throw ApiException(
                ApiErrorCode.TOO_MANY_REQUESTS,
                "Too many uploads are in progress; finish them or retry after they expire",
            )
        }
        val ttl = storageProperties.presignTtl
        val key = UUID.randomUUID().toString() + "." + kind.extension
        val presigned = storage.presignPut(key, kind.contentType, ttl)
            ?: throw ApiException(
                ApiErrorCode.SERVICE_UNAVAILABLE,
                "Direct upload is not available on this deployment; use the multipart upload endpoint",
            )
        val row = uploads.save(
            MediaUploadEntity().apply {
                ownerId = owner.id
                storageKey = key
                declaredKind = kind.name
                this.purpose = purpose.name
                status = STATUS_PENDING
                expiresAt = clock.instant().plus(ttl).plus(UPLOAD_GRACE)
            }
        )
        return MediaUploadTicket(
            uploadId = row.id.toString(),
            uploadUrl = presigned.url,
            method = presigned.method,
            headers = presigned.headers,
            key = key,
            expiresAt = row.expiresAt.toEpochMilli(),
            confirmUrl = "/media/uploads/" + row.id + "/confirm",
        )
    }

    @Transactional(noRollbackFor = [ApiException::class])
    fun confirm(owner: UserEntity, uploadIdRaw: String, baseUrl: String?): MediaUploadResponsePayload {
        val row = uploads.findById(parseUuid(uploadIdRaw)).orElse(null) ?: throw notFound("Upload not found")
        if (row.ownerId != owner.id) throw ApiException(ApiErrorCode.FORBIDDEN, "Not your upload")
        val kind = MediaKind.valueOf(row.declaredKind)
        if (row.status == STATUS_VERIFIED) return payload(row, kind, baseUrl)
        if (row.status == STATUS_REJECTED) throw invalidArgument("This upload was rejected")
        val limit = maxBytes(row.purpose)
        val metadata = storage.head(row.storageKey) ?: reject(row, "The uploaded file was not found")
        if (metadata.sizeBytes <= 0L) reject(row, "The uploaded file is empty")
        if (metadata.sizeBytes > limit) {
            reject(row, "File must be " + (limit / MediaPolicies.BYTES_PER_MB) + " MB or smaller")
        }
        val prefix = storage.getRange(row.storageKey, 0L, DETECT_PREFIX_BYTES)
            ?: reject(row, "The uploaded file could not be read")
        val detected = MediaContentTypes.detect(prefix) ?: reject(row, "Unsupported or unrecognised file type")
        if (detected != kind) reject(row, "The file content does not match the declared type")
        row.status = STATUS_VERIFIED
        row.sizeBytes = metadata.sizeBytes
        uploads.save(row)
        return payload(row, kind, baseUrl)
    }

    /** Deletes the object of every PENDING ticket whose presigned window passed. */
    @Transactional
    fun sweep(): Int {
        val stale = uploads.findAllByStatusAndExpiresAtBefore(STATUS_PENDING, clock.instant())
        stale.forEach { row ->
            runCatching { storage.delete(row.storageKey) }
            uploads.delete(row)
        }
        return stale.size
    }

    /** Deletes the object, records the rejection, and fails the request. */
    private fun reject(row: MediaUploadEntity, message: String): Nothing {
        runCatching { storage.delete(row.storageKey) }
        row.status = STATUS_REJECTED
        uploads.save(row)
        throw invalidArgument(message)
    }

    private fun payload(row: MediaUploadEntity, kind: MediaKind, baseUrl: String?): MediaUploadResponsePayload =
        MediaUploadResponsePayload(
            url = baseUrl?.trim()?.trimEnd('/').orEmpty() + "/media/" + row.storageKey,
            mediaType = when {
                kind.isVideo -> "VIDEO"
                kind.isImage -> "IMAGE"
                else -> "FILE"
            },
        )

    private fun maxBytes(purpose: String): Long =
        if (parsePurposeOrNull(purpose) == MediaPurpose.MEDIA) maxUploadBytes else MediaPolicies.ATTACHMENT_MAX_BYTES

    private fun requirePurpose(owner: UserEntity, purpose: MediaPurpose) {
        if (purpose == MediaPurpose.DOCUMENT && owner.role != Role.TEACHER && owner.role != Role.ADMIN) {
            throw ApiException(ApiErrorCode.FORBIDDEN, "Only teachers may upload documents")
        }
    }

    private fun parsePurpose(raw: String): MediaPurpose =
        parsePurposeOrNull(raw) ?: throw invalidArgument("purpose must be MEDIA, HOMEWORK_ATTACHMENT or DOCUMENT")

    private fun parsePurposeOrNull(raw: String): MediaPurpose? =
        runCatching { MediaPurpose.valueOf(raw.trim().uppercase()) }.getOrNull()

    private fun parseUuid(raw: String): UUID =
        runCatching { UUID.fromString(raw.trim()) }.getOrNull()
            ?: throw invalidArgument("uploadId is not a valid identifier")

    private companion object {
        const val STATUS_PENDING = "PENDING"
        const val STATUS_VERIFIED = "VERIFIED"
        const val STATUS_REJECTED = "REJECTED"

        /** Enough of the file to identify a ZIP-based DOCX/EPUB as well as a header. */
        const val DETECT_PREFIX_BYTES = 64 * 1024
        const val MAX_PENDING_PER_USER = 10L
        val UPLOAD_GRACE: Duration = Duration.ofMinutes(15)
    }
}
