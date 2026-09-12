package com.afrithecus.brainbox.api.announcement.repository

import com.afrithecus.brainbox.api.announcement.entity.AnnouncementViewEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface AnnouncementViewRepository : JpaRepository<AnnouncementViewEntity, UUID> {

    fun findAllByAnnouncementId(announcementId: UUID): List<AnnouncementViewEntity>

    fun findByAnnouncementIdAndStudentId(announcementId: UUID, studentId: UUID): AnnouncementViewEntity?
}
