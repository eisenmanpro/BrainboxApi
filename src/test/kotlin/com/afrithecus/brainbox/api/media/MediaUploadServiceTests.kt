package com.afrithecus.brainbox.api.media

import com.afrithecus.brainbox.api.common.error.ApiErrorCode
import com.afrithecus.brainbox.api.common.error.ApiException
import com.afrithecus.brainbox.api.identity.entity.SchoolEntity
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.model.Role
import com.afrithecus.brainbox.api.identity.repository.SchoolRepository
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import com.afrithecus.brainbox.api.media.repository.MediaUploadRepository
import com.afrithecus.brainbox.api.media.web.MediaUploadInitiateRequest
import com.afrithecus.brainbox.api.storage.ObjectMetadata
import com.afrithecus.brainbox.api.storage.ObjectStorage
import com.afrithecus.brainbox.api.storage.PresignedUpload
import com.afrithecus.brainbox.api.storage.StorageProperties
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.util.UUID
import kotlin.test.assertFailsWith

/**
 * Presigned client-direct upload: the ticket, the verify-after-upload step that
 * replaces the proxied path's mid-flight inspection, rejection that deletes the
 * object, the teacher-only document purpose and the abandoned-upload sweep.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class MediaUploadServiceTests(
    @Autowired private val uploads: MediaUploadRepository,
    @Autowired private val storageProperties: StorageProperties,
    @Autowired private val clock: Clock,
    @Autowired private val users: UserRepository,
    @Autowired private val schools: SchoolRepository,
) {

    private val storage = FakeObjectStorage()
    private lateinit var service: MediaUploadService
    private lateinit var student: UserEntity
    private lateinit var teacher: UserEntity

    @BeforeEach
    fun setUp() {
        service = MediaUploadService(storage, uploads, storageProperties, clock, 25L * 1024 * 1024)
        storage.objects.clear()
        storage.presignEnabled = true
        val school = schools.save(SchoolEntity().apply { name = "Media Upload School"; isActive = true })
        student = user(Role.STUDENT, "media.student@test", school.id)
        teacher = user(Role.TEACHER, "media.teacher@test", school.id)
    }

    @Test
    fun initiateReturnsATicketAndRecordsAPendingRow() {
        val ticket = service.initiate(student, MediaUploadInitiateRequest(contentType = "image/png"))
        check(ticket.key.endsWith(".png")) { "the key carries the declared type's extension" }
        check(ticket.method == "PUT")
        check(ticket.headers["Content-Type"] == "image/png")
        check(ticket.uploadUrl.contains(ticket.key))
        check(ticket.confirmUrl.endsWith("/confirm"))

        val row = uploads.findById(UUID.fromString(ticket.uploadId)).orElseThrow()
        check(row.status == "PENDING")
        check(row.ownerId == student.id)
        check(row.declaredKind == "PNG")
        check(row.storageKey == ticket.key)
    }

    @Test
    fun confirmVerifiesTheRealFormatAndServesTheFile() {
        val ticket = service.initiate(student, MediaUploadInitiateRequest(contentType = "image/png"))
        storage.put(ticket.key, PNG, "image/png")

        val payload = service.confirm(student, ticket.uploadId, "https://api.test")
        check(payload.mediaType == "IMAGE")
        check(payload.url == "https://api.test/media/" + ticket.key)
        check(uploads.findById(UUID.fromString(ticket.uploadId)).orElseThrow().status == "VERIFIED")
        check(storage.objects.containsKey(ticket.key)) { "a verified object stays in the store" }

        // Confirm is idempotent: a retry returns the same file without re-verifying.
        val again = service.confirm(student, ticket.uploadId, "https://api.test")
        check(again.url == payload.url)
    }

    @Test
    fun confirmRejectsAndDeletesAPayloadThatDoesNotMatchTheDeclaredType() {
        val ticket = service.initiate(student, MediaUploadInitiateRequest(contentType = "image/png"))
        storage.put(ticket.key, "<script>alert(1)</script>".toByteArray(), "image/png")

        val failure = assertFailsWith<ApiException> { service.confirm(student, ticket.uploadId, null) }
        check(failure.code == ApiErrorCode.INVALID_ARGUMENT) { "a mismatch is a client error, was " + failure.code }
        check(!storage.objects.containsKey(ticket.key)) { "a rejected object must be deleted" }
        check(uploads.findById(UUID.fromString(ticket.uploadId)).orElseThrow().status == "REJECTED")
    }

    @Test
    fun confirmRejectsAnOversizedFile() {
        val small = MediaUploadService(storage, uploads, storageProperties, clock, 4L)
        val ticket = small.initiate(student, MediaUploadInitiateRequest(contentType = "image/png"))
        storage.put(ticket.key, PNG, "image/png")

        assertFailsWith<ApiException> { small.confirm(student, ticket.uploadId, null) }
        check(!storage.objects.containsKey(ticket.key))
        check(uploads.findById(UUID.fromString(ticket.uploadId)).orElseThrow().status == "REJECTED")
    }

    @Test
    fun aBackendThatCannotPresignFailsTheInitiate() {
        storage.presignEnabled = false
        val failure = assertFailsWith<ApiException> {
            service.initiate(student, MediaUploadInitiateRequest(contentType = "image/png"))
        }
        check(failure.code == ApiErrorCode.SERVICE_UNAVAILABLE)
    }

    @Test
    fun anotherUserCannotConfirmTheUpload() {
        val ticket = service.initiate(student, MediaUploadInitiateRequest(contentType = "image/png"))
        storage.put(ticket.key, PNG, "image/png")
        val failure = assertFailsWith<ApiException> { service.confirm(teacher, ticket.uploadId, null) }
        check(failure.code == ApiErrorCode.FORBIDDEN)
    }

    @Test
    fun documentUploadsAreTeacherOnly() {
        val denied = assertFailsWith<ApiException> {
            service.initiate(student, MediaUploadInitiateRequest(contentType = "application/pdf", purpose = "DOCUMENT"))
        }
        check(denied.code == ApiErrorCode.FORBIDDEN)

        val allowed = service.initiate(teacher, MediaUploadInitiateRequest(contentType = "application/pdf", purpose = "DOCUMENT"))
        check(allowed.key.endsWith(".pdf"))
    }

    @Test
    fun unsupportedTypesAreRejectedAtInitiate() {
        val failure = assertFailsWith<ApiException> {
            service.initiate(student, MediaUploadInitiateRequest(contentType = "application/x-msdownload"))
        }
        check(failure.code == ApiErrorCode.INVALID_ARGUMENT)
    }

    @Test
    fun sweepDeletesTheObjectOfAnUnconfirmedUpload() {
        val ticket = service.initiate(student, MediaUploadInitiateRequest(contentType = "image/png"))
        storage.put(ticket.key, PNG, "image/png")
        val row = uploads.findById(UUID.fromString(ticket.uploadId)).orElseThrow()
        row.expiresAt = clock.instant().minus(Duration.ofMinutes(1))
        uploads.save(row)

        val removed = service.sweep()
        check(removed >= 1)
        check(!storage.objects.containsKey(ticket.key)) { "the abandoned object must be deleted" }
        check(uploads.findById(UUID.fromString(ticket.uploadId)).isEmpty)
    }

    // ------------------------------------------------------------- fixtures

    private fun user(role: Role, email: String, schoolId: UUID): UserEntity = users.save(
        UserEntity().apply {
            phoneNumber = "07" + (10000000 + users.count()).toString()
            this.email = email
            passwordHash = "x"
            name = "Media User " + role.name
            this.role = role
            this.schoolId = schoolId
            isVerified = true
            isActive = true
        }
    )

    class FakeObjectStorage : ObjectStorage {
        val objects = mutableMapOf<String, ByteArray>()
        var presignEnabled = true

        override fun put(key: String, bytes: ByteArray, contentType: String) {
            objects[key] = bytes
        }

        override fun get(key: String): ByteArray? = objects[key]

        override fun delete(key: String) {
            objects.remove(key)
        }

        override fun presignPut(key: String, contentType: String, ttl: Duration): PresignedUpload? =
            if (presignEnabled) PresignedUpload("https://store.test/" + key, "PUT", mapOf("Content-Type" to contentType)) else null

        override fun head(key: String): ObjectMetadata? =
            objects[key]?.let { ObjectMetadata(it.size.toLong(), null) }

        override fun getRange(key: String, offset: Long, length: Int): ByteArray? {
            val bytes = objects[key] ?: return null
            val start = offset.toInt().coerceIn(0, bytes.size)
            val end = minOf(bytes.size, start + length)
            return bytes.copyOfRange(start, end)
        }
    }

    private companion object {
        val PNG = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    }
}
