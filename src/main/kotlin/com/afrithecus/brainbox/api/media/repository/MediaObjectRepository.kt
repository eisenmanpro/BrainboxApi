package com.afrithecus.brainbox.api.media.repository

import com.afrithecus.brainbox.api.media.entity.MediaObjectEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant

interface MediaObjectRepository : JpaRepository<MediaObjectEntity, String> {

    /** Unclaimed proxy uploads old enough to reap. */
    fun findAllByReferencedAtIsNullAndCreatedAtBefore(cutoff: Instant): List<MediaObjectEntity>
}
