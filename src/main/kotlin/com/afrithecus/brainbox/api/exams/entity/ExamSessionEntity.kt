package com.afrithecus.brainbox.api.exams.entity

import com.afrithecus.brainbox.api.exams.model.SessionStatus
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/** One exam attempt per (user, exam); idempotency key for offline sync (doc 02 §3.2). */
@Entity
@Table(name = "exam_sessions")
class ExamSessionEntity {

    @Id
    @Column(nullable = false, updatable = false)
    var id: UUID = UUID.randomUUID()

    @Column(name = "exam_id", nullable = false)
    var examId: UUID = UUID.randomUUID()

    @Column(name = "user_id", nullable = false)
    var userId: UUID = UUID.randomUUID()

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    var status: SessionStatus = SessionStatus.IN_PROGRESS

    @Column(name = "current_index", nullable = false)
    var currentIndex: Int = 0

    /** JSON object string: {"questionId":"answer"} */
    @Column(columnDefinition = "text")
    var answers: String? = null

    /** JSON array string: ["questionId"] */
    @Column(columnDefinition = "text")
    var flagged: String? = null

    @Column(name = "started_at", nullable = false, updatable = false)
    var startedAt: Instant = Instant.now()

    @Column(name = "completed_at")
    var completedAt: Instant? = null

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now()
}
