package com.afrithecus.brainbox.api.storage

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * Object-storage settings. LOCAL (the default) keeps the current on-disk behaviour
 * under the existing media/report directories; S3 points both stores at an
 * S3-compatible endpoint such as MinIO, so media and report bytes live in one
 * shared store instead of a single node's disk.
 */
@ConfigurationProperties(prefix = "app.storage")
data class StorageProperties(
    val provider: StorageProvider = StorageProvider.LOCAL,
    /** Lifetime of a presigned direct-download URL. */
    val presignTtl: Duration = Duration.ofMinutes(10),
    val s3: S3 = S3(),
) {

    enum class StorageProvider { LOCAL, S3 }

    data class S3(
        val endpoint: String = "",
        val bucket: String = "",
        val region: String = "us-east-1",
        val accessKey: String = "",
        val secretKey: String = "",
        /** MinIO and most self-hosted gateways use path-style addressing. */
        val pathStyle: Boolean = true,
    )
}
