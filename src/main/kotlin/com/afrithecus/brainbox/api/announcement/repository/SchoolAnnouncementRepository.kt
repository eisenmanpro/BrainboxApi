package com.afrithecus.brainbox.api.announcement.repository

import com.afrithecus.brainbox.api.announcement.entity.SchoolAnnouncementEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface SchoolAnnouncementRepository : JpaRepository<SchoolAnnouncementEntity, UUID> {

    fun findAllBySchoolIdOrderByPostedAtDesc(schoolId: UUID): List<SchoolAnnouncementEntity>
}
