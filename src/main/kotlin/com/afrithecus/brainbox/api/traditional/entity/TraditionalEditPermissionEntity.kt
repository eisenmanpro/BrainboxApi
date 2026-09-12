package com.afrithecus.brainbox.api.traditional.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/** A one-time coordinator grant letting a teacher edit one student's mark (doc 10 §5). */
@Entity
@Table(name = "traditional_edit_permissions")
class TraditionalEditPermissionEntity {

    @Id
    @Column(nullable = false, updatable = false)
    var id: UUID = UUID.randomUUID()

    @Column(name = "exam_id", nullable = false)
    var examId: UUID = UUID.randomUUID()

    @Column(name = "student_id", nullable = false)
    var studentId: UUID = UUID.randomUUID()

    @Column(name = "teacher_id", nullable = false)
    var teacherId: UUID = UUID.randomUUID()

    @Column(name = "granted_by", nullable = false)
    var grantedBy: UUID = UUID.randomUUID()

    @Column(name = "granted_at", nullable = false)
    var grantedAt: Instant = Instant.now()

    @Column(name = "expires_at", nullable = false)
    var expiresAt: Instant = Instant.now()

    @Column(nullable = false)
    var used: Boolean = false
}
