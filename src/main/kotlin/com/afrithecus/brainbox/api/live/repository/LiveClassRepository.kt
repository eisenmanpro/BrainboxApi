package com.afrithecus.brainbox.api.live.repository

import com.afrithecus.brainbox.api.live.entity.LiveClassEntity
import com.afrithecus.brainbox.api.live.model.LiveClassStatus
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface LiveClassRepository : JpaRepository<LiveClassEntity, UUID> {

    fun findAllByStatusOrderByScheduledStartAsc(status: LiveClassStatus): List<LiveClassEntity>

    fun findAllByStatusAndRecordingUrlIsNotNullOrderByScheduledStartDesc(status: LiveClassStatus): List<LiveClassEntity>

    fun findAllByOrderByScheduledStartDesc(): List<LiveClassEntity>

    fun findAllByTeacherIdOrderByScheduledStartDesc(teacherId: UUID): List<LiveClassEntity>

    fun findByClientId(clientId: String): LiveClassEntity?

    fun findAllByTeacherIdAndStatusOrderByScheduledStartDesc(
        teacherId: UUID,
        status: LiveClassStatus,
    ): List<LiveClassEntity>

    fun findAllByTeacherIdAndStatusAndRecordingUrlIsNotNullOrderByScheduledStartDesc(
        teacherId: UUID,
        status: LiveClassStatus,
    ): List<LiveClassEntity>
}
