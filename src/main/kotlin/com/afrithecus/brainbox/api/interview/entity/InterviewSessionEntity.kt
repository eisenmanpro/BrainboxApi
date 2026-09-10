package com.afrithecus.brainbox.api.interview.entity

import com.afrithecus.brainbox.api.common.jpa.BaseEntity
import com.afrithecus.brainbox.api.interview.model.InterviewMode
import com.afrithecus.brainbox.api.interview.model.InterviewStatus
import com.afrithecus.brainbox.api.interview.model.PracticeType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/** One mock-interview session; the server is authoritative on its status (doc 06 §2). */
@Entity
@Table(name = "interview_sessions")
class InterviewSessionEntity : BaseEntity() {

    @Column(name = "user_id", nullable = false)
    var userId: UUID = UUID.randomUUID()

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 48)
    var type: PracticeType = PracticeType.UNIVERSITY_INTERVIEW

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    var mode: InterviewMode = InterviewMode.Q_A

    @Column(nullable = false)
    var difficulty: Int = 1

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    var status: InterviewStatus = InterviewStatus.IN_PROGRESS

    @Column
    var score: Double? = null

    @Column(name = "started_at", nullable = false)
    var startedAt: Instant = Instant.now()

    @Column(name = "completed_at")
    var completedAt: Instant? = null
}
