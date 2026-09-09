package com.afrithecus.brainbox.api.identity.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * Server-issued teacher code (CTC). Students reference it at signup to join a
 * teacher (doc 01 §7). Codes are never generated client-side.
 */
@Entity
@Table(name = "teacher_codes")
class TeacherCodeEntity {

    @Id
    @Column(nullable = false, updatable = false)
    var id: UUID = UUID.randomUUID()

    @Column(nullable = false, length = 8)
    var code: String = ""

    @Column(name = "teacher_user_id", nullable = false)
    var teacherUserId: UUID = UUID.randomUUID()

    @Column(name = "school_id")
    var schoolId: UUID? = null

    @Column(nullable = false)
    var active: Boolean = true

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant = Instant.now()
}
