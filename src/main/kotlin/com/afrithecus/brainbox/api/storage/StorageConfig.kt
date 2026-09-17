package com.afrithecus.brainbox.api.storage

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.nio.file.Paths
import java.time.Clock

/**
 * Builds the media and report object stores from app.storage.provider. LOCAL keeps
 * the existing on-disk directories; S3 routes both through one S3-compatible
 * endpoint with a per-use key prefix (media/, reports/).
 */
@Configuration
class StorageConfig {

    @Bean("mediaObjectStorage")
    fun mediaObjectStorage(
        storage: StorageProperties,
        @Value("\${app.media.local-dir:./data/media}") localDir: String,
        clock: Clock,
    ): ObjectStorage = build(storage, localDir, MEDIA_PREFIX, clock)

    @Bean("reportObjectStorage")
    fun reportObjectStorage(
        storage: StorageProperties,
        @Value("\${app.reports.storage-dir:./data/reports}") localDir: String,
        clock: Clock,
    ): ObjectStorage = build(storage, localDir, REPORTS_PREFIX, clock)

    private fun build(storage: StorageProperties, localDir: String, prefix: String, clock: Clock): ObjectStorage =
        when (storage.provider) {
            StorageProperties.StorageProvider.LOCAL -> LocalObjectStorage(Paths.get(localDir))
            StorageProperties.StorageProvider.S3 -> S3ObjectStorage(
                storage.s3,
                prefix,
                AwsSignatureV4(storage.s3.accessKey, storage.s3.secretKey, storage.s3.region),
                clock,
            )
        }

    private companion object {
        const val MEDIA_PREFIX = "media"
        const val REPORTS_PREFIX = "reports"
    }
}
