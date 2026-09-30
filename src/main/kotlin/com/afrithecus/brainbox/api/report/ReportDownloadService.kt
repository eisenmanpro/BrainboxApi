package com.afrithecus.brainbox.api.report

import com.afrithecus.brainbox.api.common.error.ApiErrorCode
import com.afrithecus.brainbox.api.common.error.ApiException
import com.afrithecus.brainbox.api.identity.model.Role
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import com.afrithecus.brainbox.api.report.entity.ReportDownloadEntity
import com.afrithecus.brainbox.api.report.repository.ReportDownloadRepository
import com.afrithecus.brainbox.api.report.web.ReportQuotaPayload
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.time.Clock
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Short-lived signed download links and the server-authoritative lifetime
 * allowance for per-student reports (docs/ongoing/product_ops_roadmap.md item 1).
 *
 * Policy:
 * - Aggregate kinds (grade analysis, grade combined, class list, ...) are FREE and
 *   unlimited; the previous weekly quota is retired.
 * - Per-student exports are metered against [ReportProperties.studentReportLifetimeCap]
 *   for TEACHER accounts only. Learners, parents and platform admins download freely.
 * - A multi-student bulk job (studentCount > 1) was already coverage-gated at
 *   generation time; it is recorded as FREE because it is a school-level
 *   entitlement, not a personal export.
 */
@Service
class ReportDownloadService(
    private val properties: ReportProperties,
    private val downloadRepository: ReportDownloadRepository,
    private val userRepository: UserRepository,
    private val clock: Clock,
) {

    private val encoder: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder: Base64.Decoder = Base64.getUrlDecoder()

    /** A signed, expiring token for one job file. */
    fun sign(jobId: UUID): String {
        val expiresAt = clock.instant().plus(properties.downloadTtl).toEpochMilli()
        val payload = jobId.toString() + ":" + expiresAt
        return encoder.encodeToString(payload.toByteArray(Charsets.UTF_8)) + "." + encoder.encodeToString(hmac(payload))
    }

    fun verify(jobId: UUID, token: String?): Boolean {
        if (token.isNullOrBlank()) return false
        val parts = token.split('.')
        if (parts.size != 2) return false
        val payload = runCatching { String(decoder.decode(parts[0]), Charsets.UTF_8) }.getOrNull() ?: return false
        val provided = runCatching { decoder.decode(parts[1]) }.getOrNull() ?: return false
        if (!MessageDigest.isEqual(hmac(payload), provided)) return false
        val separator = payload.lastIndexOf(':')
        if (separator <= 0) return false
        val id = runCatching { UUID.fromString(payload.substring(0, separator)) }.getOrNull() ?: return false
        val expiresAt = payload.substring(separator + 1).toLongOrNull() ?: return false
        if (id != jobId) return false
        return expiresAt > clock.instant().toEpochMilli()
    }

    @Transactional(readOnly = true)
    fun quota(ownerId: UUID): ReportQuotaPayload {
        if (!isMetered(ownerId)) {
            return ReportQuotaPayload(limit = 0, used = 0, remaining = 0, resetsAt = null, metered = false)
        }
        val limit = properties.studentReportLifetimeCap
        val used = downloadRepository.sumStudentUnits(ownerId).toInt()
        return ReportQuotaPayload(
            limit = limit,
            used = used,
            remaining = (limit - used).coerceAtLeast(0),
            resetsAt = null,
            metered = true,
        )
    }

    /**
     * Records one download and returns the remaining lifetime allowance. The
     * owner's row is locked for the transaction so two concurrent downloads (same
     * node or different instances) cannot both pass the cap. Throws
     * [ApiErrorCode.TOO_MANY_REQUESTS] when the metered allowance is exhausted.
     */
    @Transactional
    fun reserve(ownerId: UUID, jobId: UUID, scope: ReportDownloadScope, studentCount: Int): Int {
        val owner = userRepository.findByIdForUpdate(ownerId)
            ?: throw ApiException(ApiErrorCode.SERVICE_UNAVAILABLE, "Report owner no longer exists")

        if (scope == ReportDownloadScope.FREE || owner.role != Role.TEACHER || studentCount > 1) {
            record(ownerId, jobId, "FREE", 0)
            return lifetimeRemaining(ownerId)
        }

        val units = studentCount.coerceAtLeast(1)
        val limit = properties.studentReportLifetimeCap
        val used = downloadRepository.sumStudentUnits(ownerId).toInt()
        if (used + units > limit) {
            throw ApiException(
                ApiErrorCode.TOO_MANY_REQUESTS,
                "Student report download limit reached (" + limit + " lifetime)",
            )
        }
        record(ownerId, jobId, "STUDENT", units)
        return (limit - used - units).coerceAtLeast(0)
    }

    private fun lifetimeRemaining(ownerId: UUID): Int =
        if (!isMetered(ownerId)) {
            0
        } else {
            (properties.studentReportLifetimeCap - downloadRepository.sumStudentUnits(ownerId).toInt())
                .coerceAtLeast(0)
        }

    private fun record(ownerId: UUID, jobId: UUID, scope: String, studentCount: Int) {
        downloadRepository.save(
            ReportDownloadEntity().apply {
                this.ownerId = ownerId
                this.jobId = jobId
                this.scope = scope
                this.studentCount = studentCount
                downloadedAt = clock.instant()
            }
        )
    }

    private fun isMetered(ownerId: UUID): Boolean =
        userRepository.findById(ownerId).map { it.role == Role.TEACHER }.orElse(false)

    private fun hmac(payload: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(properties.downloadSecret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(payload.toByteArray(Charsets.UTF_8))
    }
}
