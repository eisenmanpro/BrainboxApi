package com.afrithecus.brainbox.api.common.web

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.web.servlet.support.ServletUriComponentsBuilder

/**
 * Builds the absolute URLs the API hands to clients: served media keys and signed report
 * download links.
 *
 * `app.public-base-url` (env `PUBLIC_BASE_URL`) is the deployment knob. Empty — the
 * default — keeps the historical behaviour and uses the current request's own base, which
 * is right for a single-origin deployment. Setting it to a CDN, a reverse proxy or the
 * public object-store endpoint makes every returned link point there instead, without any
 * client change: the app treats an absolute URL as final and only resolves relative ones
 * against the API base.
 *
 * This is deliberately the only place an absolute public URL is composed, so a deployment
 * cannot end up with some links on the CDN and some on the origin.
 */
@Component
class PublicUrlBuilder(
    @Value("\${app.public-base-url:}") private val configuredBaseUrl: String,
) {

    /** The configured public base when set, otherwise the current request's base. */
    fun baseUrl(requestBaseUrl: String? = null): String {
        requestBaseUrl?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() }?.let { return it }
        configuredBaseUrl.trim().trimEnd('/').takeIf { it.isNotEmpty() }?.let { return it }
        return ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString().trimEnd('/')
    }

    /** The public URL of a stored media object. */
    fun mediaUrl(storageKey: String, requestBaseUrl: String? = null): String =
        baseUrl(requestBaseUrl) + "/media/" + storageKey

    /** True when a public/CDN base is configured, which is what the cache policy keys off. */
    fun hasPublicBaseUrl(): Boolean = configuredBaseUrl.trim().isNotEmpty()
}
