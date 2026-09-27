package com.afrithecus.brainbox.api.media

/**
 * A malware/URL scan verdict for one object.
 *
 * `SKIPPED` is a first-class outcome, not a failure: it means the deployment has no
 * scanner configured and the object was deliberately accepted unscanned. The verdict is
 * recorded on the upload row so a console can tell "clean" from "nobody looked".
 */
enum class MediaScanStatus { CLEAN, INFECTED, SKIPPED, ERROR }

data class MediaScanResult(
    val status: MediaScanStatus,
    /** The signature for an infection, or why the scan could not run. */
    val detail: String? = null,
)

/**
 * The malware/URL scanner seam.
 *
 * It runs after the format check and before an upload is marked VERIFIED, on both the
 * direct-upload confirm path and the proxied multipart path, so the two cannot disagree
 * about what they accept. An implementation must never throw for an ordinary
 * unavailability: return [MediaScanStatus.ERROR] and let the caller apply its
 * fail-open/fail-closed policy.
 */
interface MediaScanner {

    /** The configured provider name, recorded with the verdict. */
    val name: String

    /**
     * False for the no-op scanner, so a deployment without one never reads an object
     * into memory just to learn that nobody is scanning it.
     */
    val enabled: Boolean get() = true

    fun scan(bytes: ByteArray, contentType: String?): MediaScanResult
}

/** The default: no scanner configured, every object recorded as unscanned. */
class NoOpMediaScanner : MediaScanner {
    override val name: String = "none"

    override val enabled: Boolean = false

    override fun scan(bytes: ByteArray, contentType: String?): MediaScanResult =
        MediaScanResult(MediaScanStatus.SKIPPED, "No malware scanner is configured on this deployment")
}
