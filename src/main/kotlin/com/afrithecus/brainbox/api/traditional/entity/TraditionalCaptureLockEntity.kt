package com.afrithecus.brainbox.api.traditional.entity

import com.afrithecus.brainbox.api.common.jpa.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.util.UUID

/**
 * The class a teacher committed to for one capture session. A teacher captures
 * marks for exactly one class per exam; coordinators and school-wide staff keep
 * their grade-wide edit authority and are never locked (V33).
 */
@Entity
@Table(name = "traditional_capture_locks")
class TraditionalCaptureLockEntity : BaseEntity() {

    @Column(name = "exam_id", nullable = false, updatable = false)
    var examId: UUID = UUID.randomUUID()

    @Column(name = "teacher_id", nullable = false, updatable = false)
    var teacherId: UUID = UUID.randomUUID()

    @Column(name = "class_id", nullable = false, updatable = false)
    var classId: UUID = UUID.randomUUID()

    /** Human-readable class name at lock time, for the client's restored banner. */
    @Column(name = "class_tag", nullable = false, length = 128)
    var classTag: String = ""

    /** Ignored classes are archived at finalisation rather than left pending forever. */
    @Column(nullable = false)
    var archived: Boolean = false
}
