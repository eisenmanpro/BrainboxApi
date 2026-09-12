package com.afrithecus.brainbox.api.announcement.entity

import com.afrithecus.brainbox.api.common.jpa.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/** A teacher announcement (doc 04 section 6). */
@Entity
@Table(name = "teacher_announcements")
class TeacherAnnouncementEntity : BaseEntity() {

    @Column(name = "client_id", nullable = false, length = 80)
    var clientId: String = ""

    @Column(name = "teacher_id", nullable = false)
    var teacherId: UUID = UUID.randomUUID()

    @Column(name = "school_id")
    var schoolId: UUID? = null

    @Column(nullable = false, length = 200)
    var title: String = ""

    @Column(nullable = false, columnDefinition = "text")
    var content: String = ""

    @Column(name = "announcement_type", nullable = false, length = 16)
    var announcementType: String = "NOTICE"

    @Column(nullable = false, length = 16)
    var audience: String = "CLASS"

    @Column(name = "target_class_ids", columnDefinition = "text")
    var targetClassIds: String? = null

    @Column(name = "target_grade_levels", columnDefinition = "text")
    var targetGradeLevels: String? = null

    @Column(name = "target_student_ids", columnDefinition = "text")
    var targetStudentIds: String? = null

    @Column(name = "is_priority", nullable = false)
    var isPriority: Boolean = false

    @Column(name = "sent_at", nullable = false)
    var sentAt: Instant = Instant.now()

    @Column(name = "scheduled_at")
    var scheduledAt: Instant? = null

    @Column(name = "expires_at")
    var expiresAt: Instant? = null

    /** Set when the audience fan-out has run (null while held for scheduling). */
    @Column(name = "delivered_at")
    var deliveredAt: Instant? = null
}
