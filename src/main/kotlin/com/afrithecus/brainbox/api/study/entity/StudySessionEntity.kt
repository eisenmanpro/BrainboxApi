package com.afrithecus.brainbox.api.study.entity

import com.afrithecus.brainbox.api.common.jpa.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/** One recorded study session (doc 03 §9.1). */
@Entity
@Table(name = "study_sessions")
class StudySessionEntity : BaseEntity() {

    @Column(name = "user_id", nullable = false)
    var userId: UUID = UUID.randomUUID()

    @Column(nullable = false, length = 128)
    var subject: String = ""

    @Column(nullable = false, length = 200)
    var topic: String = ""

    @Column(name = "start_time", nullable = false)
    var startTime: Instant = Instant.now()

    @Column(name = "end_time", nullable = false)
    var endTime: Instant = Instant.now()

    /** Derived from start/end; the client value is only a hint. */
    @Column(name = "duration_minutes", nullable = false)
    var durationMinutes: Int = 0

    @Column(name = "focus_score", nullable = false)
    var focusScore: Int = 0
}
