package com.afrithecus.brainbox.api.media.repository

import com.afrithecus.brainbox.api.media.entity.MediaUploadEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant
import java.util.UUID

interface MediaUploadRepository : JpaRepository<MediaUploadEntity, UUID> {

    fun findAllByStatusAndExpiresAtBefore(status: String, cutoff: Instant): List<MediaUploadEntity>

    fun countByOwnerIdAndStatus(ownerId: UUID, status: String): Long
}
