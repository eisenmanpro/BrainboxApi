package com.afrithecus.brainbox.api.identity.repository

import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.model.AccountKind
import com.afrithecus.brainbox.api.identity.model.Role
import com.afrithecus.brainbox.api.identity.model.SubRole
import jakarta.persistence.LockModeType
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

interface UserRepository : JpaRepository<UserEntity, UUID> {

    /**
     * Locks the user's row for the duration of the transaction. Used to serialize
     * an owner's report-download quota check-and-insert across concurrent requests
     * and across API instances.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM UserEntity u WHERE u.id = :id")
    fun findByIdForUpdate(@Param("id") id: UUID): UserEntity?

    /**
     * Admin listing with optional role/school/active filters (no free-text query).
     *
     * G1.1: this is deliberately separate from [searchWithQuery]. Hibernate 7 infers a
     * null String parameter inside LOWER(CONCAT('%', :q, '%')) as bytea on Postgres, so
     * passing q = null made GET /admin/users and GET /admin/schools/{id}/teachers answer
     * 500 ("function lower(bytea) does not exist"). Keeping the null case in a query that
     * never mentions the text parameter removes the ambiguity.
     */
    @Query(
        """
        SELECT u FROM UserEntity u WHERE
        (:role IS NULL OR u.role = :role) AND
        (:schoolId IS NULL OR u.schoolId = :schoolId) AND
        (:active IS NULL OR u.isActive = :active)
        """
    )
    fun search(
        @Param("role") role: Role?,
        @Param("schoolId") schoolId: UUID?,
        @Param("active") active: Boolean?,
        pageable: Pageable,
    ): Page<UserEntity>

    /** Admin listing with a non-null free-text query (name/phone/admission). See [search]. */
    @Query(
        """
        SELECT u FROM UserEntity u WHERE
        (:role IS NULL OR u.role = :role) AND
        (:schoolId IS NULL OR u.schoolId = :schoolId) AND
        (:active IS NULL OR u.isActive = :active) AND
        (LOWER(u.name) LIKE LOWER(CONCAT('%', :q, '%')) OR
         LOWER(u.phoneNumber) LIKE LOWER(CONCAT('%', :q, '%')) OR
         LOWER(u.studentAdmissionNumber) LIKE LOWER(CONCAT('%', :q, '%')))
        """
    )
    fun searchWithQuery(
        @Param("role") role: Role?,
        @Param("schoolId") schoolId: UUID?,
        @Param("active") active: Boolean?,
        @Param("q") q: String,
        pageable: Pageable,
    ): Page<UserEntity>

    fun countBySchoolIdAndRole(schoolId: UUID, role: Role): Long

    /** Whether the school already has an active account in a given sub-role (e.g. its ICT admin). */
    fun existsBySchoolIdAndSubRoleAndIsActiveTrue(schoolId: UUID, subRole: SubRole): Boolean

    /** Active accounts in a sub-role (the one-coordinator-per-grade rule reads this list). */
    fun findAllBySchoolIdAndSubRoleAndIsActiveTrue(schoolId: UUID, subRole: SubRole): List<UserEntity>

    /** Active accounts by role; used by the renewal-reminder sweep. */
    fun findAllByRoleAndIsActiveTrue(role: Role): List<UserEntity>

    fun findAllBySchoolIdAndIsActiveTrueOrderByNameAsc(schoolId: UUID): List<UserEntity>

    /**
     * Every account of one role in a school, **any** active/verification state.
     *
     * The ICT admin's teacher lists need the rows the active-only query drops: a REJECTED
     * or FROZEN decision sets `isActive = false`, and those rows are exactly what the
     * rejected/managed tabs render.
     */
    fun findAllBySchoolIdAndRoleOrderByNameAsc(schoolId: UUID, role: Role): List<UserEntity>

    fun findAllBySchoolIdAndGradeLevelAndRole(schoolId: UUID, gradeLevel: String, role: Role): List<UserEntity>

    fun findAllByGradeLevelAndRole(gradeLevel: String, role: Role): List<UserEntity>

    /** Provisioned (roster-only) learners for one class, in name order. */
    fun findAllByProvisionedClassIdOrderByNameAsc(provisionedClassId: UUID): List<UserEntity>

    /**
     * Roster-only learners provisioned into a class: they carry `provisioned_class_id` and
     * deliberately have no class-membership row. Only [AccountKind.ROSTER_ONLY] rows count —
     * a learner upgraded to a full account keeps the stale id but belongs to their real
     * memberships (see AdminLearnerService.classesOf), so counting it here would double-count.
     */
    fun countByProvisionedClassIdAndAccountKind(provisionedClassId: UUID, accountKind: AccountKind): Long

    /** Batch form of [countByProvisionedClassIdAndAccountKind] for class lists. */
    fun findAllByProvisionedClassIdInAndAccountKind(
        provisionedClassId: Collection<UUID>,
        accountKind: AccountKind,
    ): List<UserEntity>

    fun findByPhoneNumber(phoneNumber: String): UserEntity?

    fun findByEmail(email: String): UserEntity?

    /** Emails are stored lower-cased, but legacy rows may not be: match case-insensitively. */
    fun findByEmailIgnoreCase(email: String): UserEntity?

    fun findByStudentAdmissionNumber(admissionNumber: String): UserEntity?

    /** RO-09 replay key: the client id a queued provisioning write carried. */
    fun findByProvisioningClientId(clientId: String): UserEntity?

    fun existsByPhoneNumber(phoneNumber: String): Boolean

    fun existsByEmail(email: String): Boolean

    fun existsByEmailIgnoreCase(email: String): Boolean

    /** Linked children for a parent (doc 01 §6). */
    fun findByParentUserId(parentUserId: UUID): List<UserEntity>

    /** Students who joined via a teacher code belonging to this teacher. */
    fun findByJoinedTeacherId(joinedTeacherId: UUID): List<UserEntity>
}
