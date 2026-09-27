package com.afrithecus.brainbox.api.media.security

import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface MediaScanLogRepository : JpaRepository<MediaScanLogEntity, UUID> {

    fun findAllByOrderByCreatedAtDesc(pageable: Pageable): List<MediaScanLogEntity>

    fun findAllByStatusOrderByCreatedAtDesc(status: String, pageable: Pageable): List<MediaScanLogEntity>

    fun countByStatus(status: String): Long
}

interface MediaSecurityAlertRepository : JpaRepository<MediaSecurityAlertEntity, UUID> {

    fun findAllByOrderByCreatedAtDesc(pageable: Pageable): List<MediaSecurityAlertEntity>

    fun findAllByAcknowledgedOrderByCreatedAtDesc(acknowledged: Boolean, pageable: Pageable): List<MediaSecurityAlertEntity>

    fun countByAcknowledged(acknowledged: Boolean): Long

    fun countByKindAndAcknowledged(kind: String, acknowledged: Boolean): Long
}

interface MediaQuarantineRepository : JpaRepository<MediaQuarantineEntity, UUID> {

    fun findAllByOrderByCreatedAtDesc(pageable: Pageable): List<MediaQuarantineEntity>

    fun findAllByStatusOrderByCreatedAtDesc(status: String, pageable: Pageable): List<MediaQuarantineEntity>

    fun countByStatus(status: String): Long

    fun findByUploadId(uploadId: UUID): MediaQuarantineEntity?
}
