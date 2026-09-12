package com.afrithecus.brainbox.api.attendance.entity

import com.afrithecus.brainbox.api.attendance.model.AttendanceStatus
import com.afrithecus.brainbox.api.common.jpa.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import java.time.LocalDate
import java.util.UUID

/**
 * One learner's attendance for one class on one calendar day. The unique
 * (class, day, student) key makes offline register replays idempotent.
 */
@Entity
@Table(name = "attendance_records")
class AttendanceRecordEntity : BaseEntity() {

    @Column(name = "class_id", nullable = false)
    var classId: UUID = UUID.randomUUID()

    @Column(name = "student_id", nullable = false)
    var studentId: UUID = UUID.randomUUID()

    @Column(name = "school_id")
    var schoolId: UUID? = null

    @Column(name = "attendance_date", nullable = false)
    var attendanceDate: LocalDate = LocalDate.now()

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    var status: AttendanceStatus = AttendanceStatus.PRESENT

    @Column(columnDefinition = "text")
    var notes: String? = null

    @Column(name = "recorded_by")
    var recordedBy: UUID? = null

    @Column(name = "recorded_by_name", length = 160)
    var recordedByName: String? = null

    @Column(name = "is_auto_from_live_class", nullable = false)
    var isAutoFromLiveClass: Boolean = false
}
