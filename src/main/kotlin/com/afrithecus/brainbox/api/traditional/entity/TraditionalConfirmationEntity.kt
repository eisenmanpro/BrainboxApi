package com.afrithecus.brainbox.api.traditional.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/** Per-teacher confirmation of mark entry for one exam/grade (doc 10 §4). */
@Entity
@Table(name = "traditional_confirmations")
class TraditionalConfirmationEntity {

    @Id
    @Column(nullable = false, updatable = false)
    var id: UUID = UUID.randomUUID()

    @Column(name = "exam_id", nullable = false)
    var examId: UUID = UUID.randomUUID()

    @Column(name = "teacher_id", nullable = false)
    var teacherId: UUID = UUID.randomUUID()

    @Column(name = "grade_level", nullable = false, length = 64)
    var gradeLevel: String = ""

    @Column(name = "confirmed_at")
    var confirmedAt: Instant? = null
}
