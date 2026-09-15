package com.afrithecus.brainbox.api.common.config

import jakarta.servlet.http.HttpServletRequest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.CacheControl
import org.springframework.util.AntPathMatcher
import org.springframework.web.filter.ShallowEtagHeaderFilter
import java.time.Duration

/**
 * H1 origin read caching.
 *
 * Brainbox runs in Kenyan data centres for data sovereignty, so there is NO
 * third-party CDN: every byte of read traffic terminates at the origin and the
 * origin itself must let clients revalidate cheaply.
 *
 * - [ContentReadEtagFilter] (a [ShallowEtagHeaderFilter]) derives a strong ETag
 *   from the response body of the three learner content reads below and answers a
 *   matching `If-None-Match` with `304 Not Modified` and no body. It buffers those
 *   responses in memory, which is acceptable because they are small JSON text
 *   payloads.
 * - The controllers set [CONTENT_READ_CACHE_CONTROL] separately. It is
 *   `max-age=60, must-revalidate, private`: content is immutable per generation
 *   key, but a unit can be re-projected, so a client may reuse the response for at
 *   most a minute and must then revalidate. `private` is deliberate - these are
 *   authenticated per-user responses and must never be stored by a shared cache.
 *
 * The filter applies only on the learner content-read paths, so the rest of the
 * API keeps the security chain's `Cache-Control: no-store` default.
 */
@Configuration
class HttpCachingConfig {

    @Bean
    fun contentReadEtagFilter(): ContentReadEtagFilter = ContentReadEtagFilter()

    companion object {
        /**
         * Shared read-cache policy for learner content reads. Built with
         * [CacheControl] so the header value is
         * `max-age=60, must-revalidate, private` (Spring emits directives in a
         * fixed order).
         */
        @JvmField
        val CONTENT_READ_CACHE_CONTROL: CacheControl =
            CacheControl.maxAge(Duration.ofSeconds(60)).mustRevalidate().cachePrivate()

        /**
         * The learner content reads that carry [CONTENT_READ_CACHE_CONTROL] and the
         * ETag filter. Shared with the security header writer so the two cannot drift.
         */
        @JvmField
        val CONTENT_READ_PATHS: List<String> = listOf(
            "/learning/post/*/content",
            "/materials/readable/*",
            "/practice-papers/*/content",
        )

        private val matcher = AntPathMatcher()

        /** True when [path] (already context-stripped) is an H1 content read. */
        fun isContentReadPath(path: String): Boolean =
            CONTENT_READ_PATHS.any { matcher.match(it, path) }
    }
}

/**
 * ETag generation restricted to the learner content reads.
 *
 * The servlet URL pattern grammar only supports trailing-slash-star prefix
 * mappings and star-dot extension mappings, so the middle-`*` shape of
 * `/learning/post/{postId}/content` cannot be expressed as a registration
 * pattern. The filter is registered for every request and [shouldNotFilter]
 * rejects everything outside the intended reads with an Ant matcher.
 */
class ContentReadEtagFilter : ShallowEtagHeaderFilter() {

    private val matcher = AntPathMatcher()

    override fun shouldNotFilter(request: HttpServletRequest): Boolean {
        val path = request.requestURI.removePrefix(request.contextPath)
        return !HttpCachingConfig.isContentReadPath(path)
    }
}
