package com.afrithecus.brainbox.api.teacher.repository

import com.afrithecus.brainbox.api.teacher.entity.TeacherChangeRequestEntity
import com.afrithecus.brainbox.api.teacher.model.TeacherChangeStatus
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

interface TeacherChangeRequestRepository : JpaRepository<TeacherChangeRequestEntity, UUID> {

    fun findAllByTeacherIdOrderByCreatedAtDesc(teacherId: UUID): List<TeacherChangeRequestEntity>

    fun findAllByTeacherIdAndStatusOrderByCreatedAtDesc(
        teacherId: UUID,
        status: TeacherChangeStatus,
    ): List<TeacherChangeRequestEntity>

    /** Requests a school's admin can decide, plus that school's teachers' outgoing requests. */
    @Query(
        "select r from TeacherChangeRequestEntity r " +
            "where r.schoolId = :schoolId or r.homeSchoolId = :schoolId " +
            "order by r.createdAt desc",
    )
    fun findAllForSchool(@Param("schoolId") schoolId: UUID): List<TeacherChangeRequestEntity>
}
