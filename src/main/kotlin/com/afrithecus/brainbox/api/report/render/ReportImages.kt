package com.afrithecus.brainbox.api.report.render

import com.afrithecus.brainbox.api.report.SafeImageUrl
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URI
import java.util.Base64
import javax.imageio.ImageIO

/**
 * Image loading for report branding (plan A2/A23): the school logo, the school stamp and
 * the BrainBox mark fallback. Branding images are admin-supplied, so remote fetches still
 * go through the shared SSRF policy ([SafeImageUrl]) with a short timeout and size cap.
 */
object ReportImages {

    private const val TIMEOUT_MILLIS = 2500
    private const val MAX_BYTES = 2 * 1024 * 1024

    /**
     * Optional hook installed by [ReportMediaBridge] in the running application: maps a
     * `/media/{key}` URL to bytes from the media object store. Branding images uploaded
     * through the API are served at such a URL, and a local/dev origin is not a public
     * host, so the renderer must read its own media directly instead of fetching it.
     * Null in plain unit tests; remote http(s) and data: URLs keep their normal path.
     */
    @Volatile
    internal var mediaResolver: ((String) -> ByteArray?)? = null
    private val markCache = java.util.Collections.synchronizedMap(java.util.WeakHashMap<PDDocument, PDImageXObject>())
    private val stampCache = java.util.Collections.synchronizedMap(
        java.util.WeakHashMap<PDDocument, Pair<String, PDImageXObject>>(),
    )

    /** School logo when configured and embedding is enabled; null means "use the fallback". */
    fun brandingLogo(document: PDDocument, url: String?, embed: Boolean): PDImageXObject? {
        if (!embed || url.isNullOrBlank()) return null
        val bytes = runCatching { fetch(url) }.getOrNull() ?: return null
        if (bytes.isEmpty()) return null
        return toImage(document, bytes)
    }

    /**
     * School stamp when configured (D16/A23); null means the caller draws the standard Exam
     * Office stamp. Cached per document so each report fetches a remote stamp once.
     */
    fun brandingStamp(document: PDDocument, url: String?): PDImageXObject? {
        val key = url?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        stampCache[document]?.let { (cached, image) -> if (cached == key) return image }
        val bytes = runCatching { fetch(key) }.getOrNull() ?: return null
        if (bytes.isEmpty()) return null
        val image = toImage(document, bytes) ?: return null
        stampCache[document] = key to image
        return image
    }

    /** Bundled BrainBox mark shown when a school has not uploaded a logo (decision Q10). */
    fun brainboxMark(document: PDDocument): PDImageXObject? {
        markCache[document]?.let { return it }
        val bytes = ReportImages::class.java.getResourceAsStream("/report/brainbox-icon.png")
            ?.use { it.readBytes() }
            ?: return null
        val image = toImage(document, bytes) ?: return null
        markCache[document] = image
        return image
    }

    private fun toImage(document: PDDocument, bytes: ByteArray): PDImageXObject? {
        val buffered = ImageIO.read(ByteArrayInputStream(bytes)) ?: return null
        return runCatching { LosslessFactory.createFromImage(document, buffered) }.getOrNull()
    }

    private fun fetch(url: String): ByteArray {
        if (SafeImageUrl.isDataUrl(url)) {
            val comma = url.indexOf(',')
            if (comma < 0) return ByteArray(0)
            val payload = url.substring(comma + 1)
            return if (url.substring(0, comma).contains("base64", ignoreCase = true)) {
                runCatching { Base64.getDecoder().decode(payload) }.getOrDefault(ByteArray(0))
            } else {
                payload.toByteArray(Charsets.ISO_8859_1)
            }
        }
        val stored = runCatching { mediaResolver?.invoke(url) }.getOrNull()
        if (stored != null && stored.isNotEmpty()) return stored
        if (!SafeImageUrl.isAllowed(url)) return ByteArray(0)
        val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return ByteArray(0)
        val host = SafeImageUrl.normalizeHost(uri.host) ?: return ByteArray(0)
        if (!SafeImageUrl.isPublicHost(host)) return ByteArray(0)
        val connection = runCatching { uri.toURL().openConnection() }.getOrNull() as? HttpURLConnection
            ?: return ByteArray(0)
        connection.connectTimeout = TIMEOUT_MILLIS
        connection.readTimeout = TIMEOUT_MILLIS
        connection.instanceFollowRedirects = false
        if (connection.responseCode !in 200..299) return ByteArray(0)
        val contentType = connection.contentType
        if (contentType != null && !contentType.lowercase().startsWith("image/")) return ByteArray(0)
        return connection.inputStream.use { input ->
            val bytes = input.readNBytes(MAX_BYTES + 1)
            if (bytes.size > MAX_BYTES) ByteArray(0) else bytes
        }
    }
}
