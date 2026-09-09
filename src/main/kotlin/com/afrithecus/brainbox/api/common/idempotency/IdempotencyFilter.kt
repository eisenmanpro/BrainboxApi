package com.afrithecus.brainbox.api.common.idempotency

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.util.ContentCachingResponseWrapper
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant

/**
 * Idempotent-POST protection (doc 11 §8.3/§8.4, ARCHITECTURE.md Appendix A #6).
 *
 * When a write request carries an X-Idempotency-Key header, its successful
 * response is stored (V5 idempotency_records) and replayed verbatim for any
 * later request with the same key, so offline sync replays never double-execute
 * (e.g. double-charge, duplicated submissions). Only 2xx/3xx outcomes are
 * cached; errors are never cached so a retry can still fix them.
 *
 * Registration order: RateLimitFilter, this filter, then the security chain.
 */
@Component
class IdempotencyFilter(
    private val repository: IdempotencyRepository,
    private val clock: Clock,
) : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val method = request.method
        if (method !in WRITE_METHODS) {
            filterChain.doFilter(request, response)
            return
        }
        val rawKey = request.getHeader(IDEMPOTENCY_KEY_HEADER)?.trim()
        if (rawKey.isNullOrBlank() || rawKey.length > 128) {
            filterChain.doFilter(request, response)
            return
        }

        val now = clock.instant()
        val keyHash = sha256Hex(rawKey)
        repository.findByKeyHash(keyHash)?.let { existing ->
            if (existing.expiresAt.isAfter(now)) {
                replay(response, existing)
                return
            }
        }

        val wrapper = ContentCachingResponseWrapper(response)
        filterChain.doFilter(request, wrapper)
        val status = wrapper.status
        if (status >= 200 && status < 400) {
            val bytes = wrapper.contentAsByteArray
            val body = bytes.toString(StandardCharsets.UTF_8)
            if (bytes.isNotEmpty()) {
                val record = IdempotencyRecordEntity().apply {
                    this.keyHash = keyHash
                    this.method = method
                    path = request.requestURI
                    this.status = status
                    contentType = wrapper.contentType
                    this.body = body
                    expiresAt = now.plus(RECORD_TTL)
                }
                try {
                    repository.save(record)
                } catch (ex: DataIntegrityViolationException) {
                    // Concurrent duplicate: another request already stored the record.
                }
            }
        }
        wrapper.copyBodyToResponse()
    }

    private fun replay(response: HttpServletResponse, record: IdempotencyRecordEntity) {
        response.status = record.status
        record.contentType?.let { response.contentType = it }
        record.body?.let { body ->
            val bytes = body.toByteArray(StandardCharsets.UTF_8)
            response.setContentLength(bytes.size)
            response.outputStream.write(bytes)
        }
    }

    private fun sha256Hex(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val IDEMPOTENCY_KEY_HEADER = "X-Idempotency-Key"
        val RECORD_TTL: java.time.Duration = java.time.Duration.ofHours(24)
        val WRITE_METHODS = setOf("POST", "PUT", "PATCH")
    }
}
