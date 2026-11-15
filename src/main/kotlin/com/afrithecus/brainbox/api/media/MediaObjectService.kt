package com.afrithecus.brainbox.api.media

import com.afrithecus.brainbox.api.media.entity.MediaObjectEntity
import com.afrithecus.brainbox.api.media.repository.MediaObjectRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * Lifecycle for a proxied upload whose URL is persisted by a later request
 * (school branding, D16/A23 follow-up).
 *
 * The two-phase shape is the reason this exists: the upload endpoint writes the
 * bytes immediately and the config PUT later decides whether they are used. A
 * form the admin cancels therefore leaves an unreferenced object, and replacing
 * a logo/stamp leaves the previous object behind. [register] records the upload,
 * [claim] marks the URL a saved config now owns, and [discardSuperseded] removes
 * the previous logo/stamp object the same config write replaced. The scheduled
 * [sweep] is the safety net for a browser closed between the two requests.
 * Anything not registered here (chat, homework, documents) is untouched: those
 * URLs are referenced by the request that stores them, so they are not
 * abandonable in this way.
 */
@Service
class MediaObjectService(
    private val objects: MediaObjectRepository,
    private val media: MediaService,
    private val clock: Clock,
) {

    /** Records a freshly stored proxy upload as unreferenced. Unknown URL shapes are ignored. */
    @Transactional
    fun register(mediaUrl: String, purpose: String) {
        val key = media.managedKey(mediaUrl) ?: return
        if (objects.existsById(key)) return
        objects.save(
            MediaObjectEntity().apply {
                storageKey = key
                this.purpose = purpose
                createdAt = clock.instant()
            },
        )
    }

    /** Marks a URL a persisted record now owns. A URL this table did not record is ignored. */
    @Transactional
    fun claim(mediaUrl: String?) {
        val key = mediaUrl?.let { media.managedKey(it) } ?: return
        val row = objects.findById(key).orElse(null) ?: return
        if (row.referencedAt == null) {
            row.referencedAt = clock.instant()
            objects.save(row)
        }
    }

    /**
     * Deletes a previous branding object replaced by [keep]. The media object is
     * removed immediately; only URLs this table recorded are touched, so a
     * legacy URL pasted by an admin is never destroyed.
     */
    @Transactional
    fun discardSuperseded(previous: Collection<String?>, keep: Collection<String?>) {
        val kept = keep.filterNotNull().mapNotNull { media.managedKey(it) }.toSet()
        previous.filterNotNull().distinct().forEach { url ->
            val key = media.managedKey(url) ?: return@forEach
            if (key in kept) return@forEach
            // Only an object this table recorded belongs to the two-phase lifecycle;
            // a legacy pasted URL is never deleted by a config write.
            val row = objects.findById(key).orElse(null) ?: return@forEach
            media.delete(key)
            objects.delete(row)
        }
    }

    /**
     * Reaps unclaimed uploads older than the grace period. An upload is claimed by
     * the config write that saves it, so this only catches abandoned uploads.
     */
    @Transactional
    fun sweep(cutoff: Instant = clock.instant().minus(UNCLAIMED_TTL)): Int {
        val stale = objects.findAllByReferencedAtIsNullAndCreatedAtBefore(cutoff)
        stale.forEach { row ->
            media.delete(row.storageKey)
            objects.delete(row)
        }
        return stale.size
    }

    private companion object {
        /** A long grace period: an admin may upload, get distracted and still save. */
        val UNCLAIMED_TTL: Duration = Duration.ofHours(24)
    }
}
