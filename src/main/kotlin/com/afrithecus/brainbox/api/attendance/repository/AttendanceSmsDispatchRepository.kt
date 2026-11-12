package com.afrithecus.brainbox.api.attendance.repository

import com.afrithecus.brainbox.api.attendance.entity.AttendanceSmsDispatchEntity
import com.afrithecus.brainbox.api.attendance.model.AttendanceSmsStatus
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.UUID

interface AttendanceSmsDispatchRepository : JpaRepository<AttendanceSmsDispatchEntity, UUID> {

    fun existsByAttendanceRecordIdAndRecipientUserId(recordId: UUID, recipientUserId: UUID): Boolean

    fun existsByPhoneNumberAndCreatedAtBetween(phoneNumber: String, from: Instant, to: Instant): Boolean

    fun findAllBySchoolIdAndCreatedAtBetweenOrderByCreatedAtDesc(
        schoolId: UUID,
        from: Instant,
        to: Instant,
    ): List<AttendanceSmsDispatchEntity>

    fun countBySchoolIdAndCreatedAtBetween(schoolId: UUID, from: Instant, to: Instant): Long

    fun findAllByCreatedAtBetweenOrderByCreatedAtDesc(from: Instant, to: Instant): List<AttendanceSmsDispatchEntity>

    /** P4.5: a delivery report names the provider's message id, not our dispatch id. */
    fun findByProviderMessageId(providerMessageId: String): AttendanceSmsDispatchEntity?

    /**
     * P6.0 staging simulation only: accepted messages that have not received any delivery report
     * yet and are old enough for the simulator's configured delay. Includes rows with no provider
     * message id (the logging gateway returns none), because the simulator addresses them by our
     * dispatch id. Ordered oldest-first so a capped sweep always makes progress.
     */
    @Query(
        """
        select d from AttendanceSmsDispatchEntity d
        where d.status = :status
          and d.dlrReceivedAt is null
          and d.createdAt <= :cutoff
        order by d.createdAt asc
        """,
    )
    fun findPendingDlrSimulation(
        @Param("status") status: AttendanceSmsStatus,
        @Param("cutoff") cutoff: Instant,
        pageable: Pageable,
    ): List<AttendanceSmsDispatchEntity>
}
