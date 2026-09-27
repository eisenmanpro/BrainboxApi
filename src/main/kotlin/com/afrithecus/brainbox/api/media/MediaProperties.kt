package com.afrithecus.brainbox.api.media

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Media serving and scanning settings (`app.media.*`).
 *
 * The scanner is the stage that runs after the format check and before an upload is
 * marked VERIFIED, for both the presigned and the proxied path. `provider = none`
 * (the default) records the upload as `SKIPPED` rather than pretending it was
 * scanned, so a deployment without a scanner still knows exactly which objects were
 * never checked.
 */
@ConfigurationProperties(prefix = "app.media")
data class MediaProperties(
    val cache: Cache = Cache(),
    val scan: Scan = Scan(),
    val urlReputation: UrlReputation = UrlReputation(),
) {

    /**
     * URL reputation checking. Off by default: a deployment enables it in config or from
     * the console once it has a provider to call.
     */
    data class UrlReputation(
        val enabled: Boolean = false,
        /** none, deny_list (the built-in SSRF/deny-list rules) or http (a provider). */
        val provider: String = "none",
        val failOpen: Boolean = false,
        val timeoutMillis: Long = 5_000,
        val http: Http = Http(),
    ) {

        /** A provider endpoint that answers {"status":"CLEAN"|"MALICIOUS"|"ERROR"} for a URL. */
        data class Http(
            val url: String = "",
            val bearerToken: String = "",
        )
    }

    /**
     * How the served `/media/{key}` bytes may be cached. Media keys are server-generated
     * UUIDs that are never reused, so the bytes are immutable; `public` only makes sense
     * once a CDN or reverse proxy is in front, which is why the default stays `private`.
     */
    data class Cache(
        val publicCache: Boolean = false,
        val maxAgeSeconds: Long = 30L * 24 * 60 * 60,
    )

    /**
     * The malware/URL scanner. `none` (default), `clamav` (clamd INSTREAM) or `http`
     * (a scanner sidecar that answers `{"status":"CLEAN"|"INFECTED"|"ERROR"}`).
     *
     * `failOpen` decides what happens when the scanner cannot answer: false (the
     * default) refuses the upload, true marks it `ERROR` and accepts it. A deployment
     * that has configured a scanner should leave this false.
     */
    data class Scan(
        val provider: String = "none",
        val failOpen: Boolean = false,
        val timeoutMillis: Long = 15_000,
        /** Objects larger than this are not read into memory for scanning. */
        val maxScanBytes: Long = 26214400,
        val clamav: Clamav = Clamav(),
        val http: Http = Http(),
    ) {

        /** clamd's TCP interface; INSTREAM needs no temporary file. */
        data class Clamav(
            val host: String = "127.0.0.1",
            val port: Int = 3310,
        )

        /** A scanner sidecar reached over HTTP with the raw bytes as the body. */
        data class Http(
            val url: String = "",
            val bearerToken: String = "",
        )
    }
}
