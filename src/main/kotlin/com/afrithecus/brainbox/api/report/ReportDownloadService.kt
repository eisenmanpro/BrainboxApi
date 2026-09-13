package com.afrithecus.brainbox.api.report

import com.afrithecus.brainbox.api.common.error.invalidArgument
import com.afrithecus.brainbox.api.report.repository.ReportDownloadRepository
import com.afrithecus.brainbox.api.report.repository.ReportJobRepository
import com.afrithecus.brainbox.api.report.entity.ReportDownloadEntity
import com.afrithecus.brainbox.api.report.web.ReportQuotaPayload
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.time.Clock
import java.time.DayOfWeek
import java.time.Duration
import java.time.ZoneId
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Short-lived signed download links and the server-authoritative weekly export
 * quota (docs/ongoing/api_reports_changes.md sections 3-4). The client fetches
 * fileUrl without a bearer header, so the signature is the authorization; the
 * quota ledger counts each successful download.
 */
@Service
class ReportDownloadService(
    private val properties: ReportProperties,
    private val jobRepository: ReportJobRepository,
    private val downloadRepository: ReportDownloadRepository,
    private val clock: Clock,
    @Value("\${app.school-zone:Africa/Nairobi}") private val schoolZone: String,
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
        val weekStart = weekStart()
        val used = downloadRepository.countByOwnerIdAndDownloadedAtGreaterThanEqual(ownerId, weekStart).toInt()
        val remaining = (properties.weeklyQuota - used).coerceAtLeast(0)
        return ReportQuotaPayload(
            limit = properties.weeklyQuota,
            used = used,
            remaining = remaining,
            resetsAt = weekStart.plus(Duration.ofDays(7)).toEpochMilli(),
        )
    }

    @Transactional
    fun record(ownerId: UUID, jobId: UUID) {
        jobRepository.findById(jobId).orElse(null) ?: throw invalidArgument("Report job not found")
        downloadRepository.save(
            ReportDownloadEntity().apply {
                this.ownerId = ownerId
                this.jobId = jobId
                downloadedAt = clock.instant()
            }
        )
    }

    private fun weekStart(): java.time.Instant {
        val zone = runCatching { ZoneId.of(schoolZone) }.getOrDefault(ZoneId.of("Africa/Nairobi"))
        val today = clock.instant().atZone(zone).toLocalDate()
        return today.with(DayOfWeek.MONDAY).atStartOfDay(zone).toInstant()
    }

    private fun hmac(payload: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(properties.downloadSecret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(payload.toByteArray(Charsets.UTF_8))
    }
}
