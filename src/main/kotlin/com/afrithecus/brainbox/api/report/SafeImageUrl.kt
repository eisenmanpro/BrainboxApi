package com.afrithecus.brainbox.api.report

import com.afrithecus.brainbox.api.common.net.PublicUrlPolicy
import java.net.InetAddress

/**
 * Policy for a branding-logo URL the server fetches while rendering a PDF. School
 * branding is admin-controlled, but the fetch is still a server-side outbound
 * request, so it is limited to http(s) to a public host. The SSRF rule itself is
 * shared in [PublicUrlPolicy]; this type keeps the report-specific data: URL case.
 */
object SafeImageUrl {

    /** True when [url] is an inline data: payload (decoded, never fetched). */
    fun isDataUrl(url: String): Boolean = url.trim().startsWith("data:", ignoreCase = true)

    fun isAllowed(url: String): Boolean = PublicUrlPolicy.isAllowed(url)

    fun normalizeHost(host: String?): String? = PublicUrlPolicy.normalizeHost(host)

    fun isPublicHost(host: String): Boolean = PublicUrlPolicy.isPublicHost(host)

    fun isBlockedAddress(address: InetAddress): Boolean = PublicUrlPolicy.isBlockedAddress(address)
}
