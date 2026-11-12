package com.afrithecus.brainbox.api.attendance.entity

import com.afrithecus.brainbox.api.attendance.model.AttendanceSmsStatus
import com.afrithecus.brainbox.api.common.jpa.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * One paid delivery attempt for one attendance record and recipient account. The unique
 * (record, recipient) index is the exactly-once guard; the row is also the spend ledger.
 */
@Entity
@Table(name = "attendance_sms_dispatches")
class AttendanceSmsDispatchEntity : BaseEntity() {

    @Column(name = "attendance_record_id", nullable = false)
    var attendanceRecordId: UUID = UUID.randomUUID()

    @Column(name = "school_id")
    var schoolId: UUID? = null

    @Column(name = "learner_id", nullable = false)
    var learnerId: UUID = UUID.randomUUID()

    @Column(name = "recipient_user_id", nullable = false)
    var recipientUserId: UUID = UUID.randomUUID()

    /** Canonical stored form ("0712345678"); never logged in full. */
    @Column(name = "phone_number", nullable = false, length = 32)
    var phoneNumber: String = ""

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    var status: AttendanceSmsStatus = AttendanceSmsStatus.PENDING

    @Column(length = 32)
    var provider: String? = null

    @Column(name = "provider_message_id", length = 160)
    var providerMessageId: String? = null

    /** Provider failure detail, truncated; no phone number or credential may enter this field. */
    @Column(length = 512)
    var error: String? = null

    @Column(nullable = false)
    var units: Int = 1

    @Column(name = "cost_kes", nullable = false, precision = 12, scale = 2)
    var costKes: BigDecimal = BigDecimal.ZERO

    /** P4.5: first delivery report from the provider; null when the provider sends none. */
    @Column(name = "delivered_at")
    var deliveredAt: Instant? = null

    /** P4.5: last normalised DLR status (DELIVERED/FAILED/UNKNOWN); display-only. */
    @Column(name = "dlr_status", length = 32)
    var dlrStatus: String? = null

    /** P4.5: when the last delivery report arrived; the idempotency marker for replays. */
    @Column(name = "dlr_received_at")
    var dlrReceivedAt: Instant? = null
}
