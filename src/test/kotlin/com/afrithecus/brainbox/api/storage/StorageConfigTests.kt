package com.afrithecus.brainbox.api.storage

import java.time.Clock
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class StorageConfigTests {

    private val config = StorageConfig()
    private val clock: Clock = Clock.systemUTC()

    @Test
    fun localProviderBuildsALocalStore() {
        val storage = config.mediaObjectStorage(
            StorageProperties(provider = StorageProperties.StorageProvider.LOCAL),
            "/tmp/brainbox-media-x",
            clock,
        )
        assertIs<LocalObjectStorage>(storage)
    }

    @Test
    fun s3ProviderBuildsAnS3StoreWhenConfigured() {
        val storage = config.reportObjectStorage(
            StorageProperties(
                provider = StorageProperties.StorageProvider.S3,
                s3 = StorageProperties.S3(
                    endpoint = "http://minio.internal:9000",
                    bucket = "brainbox",
                    accessKey = "key",
                    secretKey = "secret",
                ),
            ),
            "/tmp/brainbox-reports-x",
            clock,
        )
        assertIs<S3ObjectStorage>(storage)
    }

    @Test
    fun s3ProviderWithoutCredentialsFailsFast() {
        assertFailsWith<IllegalArgumentException> {
            config.reportObjectStorage(
                StorageProperties(provider = StorageProperties.StorageProvider.S3),
                "/tmp/brainbox-reports-x",
                clock,
            )
        }
    }
}
