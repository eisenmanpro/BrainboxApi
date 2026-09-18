package com.afrithecus.brainbox.api.media

import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Deletes the object of any direct-upload ticket the client never confirmed, so an
 * abandoned or hostile upload cannot accumulate in the store.
 */
@Component
class MediaUploadScheduler(private val uploads: MediaUploadService) {

    @Scheduled(initialDelay = 300_000, fixedDelay = 900_000)
    fun sweep() {
        uploads.sweep()
    }
}
