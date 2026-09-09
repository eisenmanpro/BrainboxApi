package com.afrithecus.brainbox.api.security

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Token-bucket rate limiter per client IP (behind a proxy, use the first
 * X-Forwarded-For hop when trusted). Responds 429 with the standard envelope and
 * a Retry-After header. Actuator health/error endpoints are exempt.
 */
@Component
class RateLimitFilter(
    private val properties: RateLimitProperties,
    private val envelopeWriter: SecurityEnvelopeWriter,
) : OncePerRequestFilter() {

    private val buckets = HashMap<String, TokenBucket>()
    private val lock = Any()

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val uri = request.requestURI
        if (!properties.enabled || uri.startsWith("/actuator/") || uri == "/error") {
            filterChain.doFilter(request, response)
            return
        }
        val clientKey = clientKey(request)
        val bucket = synchronized(lock) {
            buckets.getOrPut(clientKey) { TokenBucket(properties.capacity.toDouble()) }
        }
        if (bucket.tryAcquire(properties.capacity.toDouble(), properties.refillPerSecond.toDouble())) {
            filterChain.doFilter(request, response)
            return
        }
        envelopeWriter.writeTooManyRequests(request, response, RETRY_AFTER_SECONDS)
    }

    private fun clientKey(request: HttpServletRequest): String {
        val forwarded = request.getHeader("X-Forwarded-For")
        if (!forwarded.isNullOrBlank()) {
            return forwarded.substringBefore(',').trim()
        }
        return request.remoteAddr ?: "unknown"
    }

    private inner class TokenBucket(initialTokens: Double) {
        private var tokens = initialTokens
        private var lastRefillNanos = System.nanoTime()

        @Synchronized
        fun tryAcquire(capacity: Double, refillPerSecond: Double): Boolean {
            val now = System.nanoTime()
            val elapsedSeconds = (now - lastRefillNanos) / 1_000_000_000.0
            if (elapsedSeconds > 0) {
                tokens = (tokens + elapsedSeconds * refillPerSecond).coerceAtMost(capacity)
                lastRefillNanos = now
            }
            if (tokens >= 1.0) {
                tokens -= 1.0
                return true
            }
            return false
        }
    }

    private companion object {
        const val RETRY_AFTER_SECONDS = 60L
    }
}
