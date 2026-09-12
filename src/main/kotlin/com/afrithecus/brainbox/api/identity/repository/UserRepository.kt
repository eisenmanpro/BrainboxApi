package com.afrithecus.brainbox.api.identity.repository

import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.model.Role
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

interface UserRepository : JpaRepository<UserEntity, UUID> {

    /** Admin listing with optional role/school/active filters and free-text search. */
    @Query(
        """
        SELECT u FROM UserEntity u WHERE
        (:role IS NULL OR u.role = :role) AND
        (:schoolId IS NULL OR u.schoolId = :schoolId) AND
        (:active IS NULL OR u.isActive = :active) AND
        (:q IS NULL OR LOWER(u.name) LIKE LOWER(CONCAT('%', :q, '%')) OR
         LOWER(u.phoneNumber) LIKE LOWER(CONCAT('%', :q, '%')) OR
         LOWER(u.studentAdmissionNumber) LIKE LOWER(CONCAT('%', :q, '%')))
        """
    )
    fun search(
        @Param("role") role: Role?,
        @Param("schoolId") schoolId: UUID?,
        @Param("active") active: Boolean?,
        @Param("q") q: String?,
        pageable: Pageable,
    ): Page<UserEntity>

    fun countBySchoolIdAndRole(schoolId: UUID, role: Role): Long

    fun findAllBySchoolIdAndIsActiveTrueOrderByNameAsc(schoolId: UUID): List<UserEntity>

    fun findAllBySchoolIdAndGradeLevelAndRole(schoolId: UUID, gradeLevel: String, role: Role): List<UserEntity>

    fun findAllByGradeLevelAndRole(gradeLevel: String, role: Role): List<UserEntity>

    fun findByPhoneNumber(phoneNumber: String): UserEntity?

    fun findByEmail(email: String): UserEntity?

    fun findByStudentAdmissionNumber(admissionNumber: String): UserEntity?

    fun existsByPhoneNumber(phoneNumber: String): Boolean

    fun existsByEmail(email: String): Boolean

    /** Linked children for a parent (doc 01 §6). */
    fun findByParentUserId(parentUserId: UUID): List<UserEntity>

    /** Students who joined via a teacher code belonging to this teacher. */
    fun findByJoinedTeacherId(joinedTeacherId: UUID): List<UserEntity>
}
