package com.afrithecus.brainbox.api.teacher.entity

import com.afrithecus.brainbox.api.common.jpa.BaseEntity
import com.afrithecus.brainbox.api.teacher.model.TeacherChangeKind
import com.afrithecus.brainbox.api.teacher.model.TeacherChangeStatus
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * A teacher-initiated change to a school-admin-governed profile field (N3).
 * The client never writes the governed field directly: the school admin of
 * [schoolId] approving this row is what applies the change.
 */
@Entity
@Table(name = "teacher_change_requests")
class TeacherChangeRequestEntity : BaseEntity() {

    @Column(name = "teacher_id", nullable = false)
    var teacherId: UUID = UUID.randomUUID()

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    var kind: TeacherChangeKind = TeacherChangeKind.CLASS_ASSIGNMENT

    /** The school whose admin decides; for a transfer this is the destination school. */
    @Column(name = "school_id", nullable = false)
    var schoolId: UUID = UUID.randomUUID()

    /** Where the teacher is today; equal to [schoolId] for a within-school change. */
    @Column(name = "home_school_id")
    var homeSchoolId: UUID? = null

    /** JSON snapshot of the governed fields before the request, for the approval gate. */
    @Column(name = "current_value", columnDefinition = "text")
    var currentValue: String? = null

    /** JSON payload of the proposed values; shape depends on [kind]. */
    @Column(name = "proposed_value", nullable = false, columnDefinition = "text")
    var proposedValue: String = "{}"

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    var status: TeacherChangeStatus = TeacherChangeStatus.PENDING

    @Column(name = "reviewer_id")
    var reviewerId: UUID? = null

    @Column(name = "review_note", columnDefinition = "text")
    var reviewNote: String? = null

    @Column(name = "reviewed_at")
    var reviewedAt: Instant? = null
}
