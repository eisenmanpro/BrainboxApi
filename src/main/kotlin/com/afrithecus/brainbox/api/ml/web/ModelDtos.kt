package com.afrithecus.brainbox.api.ml.web

/**
 * Adaptive-difficulty ONNX model distribution.
 *
 * The app fetches this manifest, downloads [url], and verifies [sizeBytes] and
 * [sha256] before it loads the model. [url] is server-relative, so the client
 * resolves it against its API base.
 */
data class AdaptiveModelManifest(
    val name: String,
    val version: String,
    val fileName: String,
    val url: String,
    /** Lower-case hex SHA-256 of the exact bytes served at [url]. */
    val sha256: String,
    val sizeBytes: Long,
    val contentType: String = "application/octet-stream",
)
