package com.afrithecus.brainbox.api.announcement.repository

import com.afrithecus.brainbox.api.announcement.entity.TeacherAnnouncementEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant
import java.util.UUID

interface TeacherAnnouncementRepository : JpaRepository<TeacherAnnouncementEntity, UUID> {

    fun findByClientId(clientId: String): TeacherAnnouncementEntity?

    fun findAllByTeacherIdOrderBySentAtDesc(teacherId: UUID): List<TeacherAnnouncementEntity>

    fun findAllByTeacherIdNotAndSchoolIdOrderBySentAtDesc(teacherId: UUID, schoolId: UUID): List<TeacherAnnouncementEntity>

    fun findAllByDeliveredAtIsNullAndScheduledAtLessThanEqual(now: Instant): List<TeacherAnnouncementEntity>

    fun findAllBySchoolIdAndDeliveredAtIsNotNullOrderBySentAtDesc(schoolId: UUID): List<TeacherAnnouncementEntity>

    /** The delivered copy/copies of one school announcement (staff row and/or learner row). */
    fun findAllBySourceSchoolAnnouncementId(sourceSchoolAnnouncementId: UUID): List<TeacherAnnouncementEntity>
}
