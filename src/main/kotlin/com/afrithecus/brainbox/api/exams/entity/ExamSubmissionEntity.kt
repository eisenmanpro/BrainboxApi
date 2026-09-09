package com.afrithecus.brainbox.api.exams.entity

import com.afrithecus.brainbox.api.common.jpa.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/** Server-graded exam result (doc 02 §3.3). Computed server-side; client is read-only. */
@Entity
@Table(name = "exam_submissions")
class ExamSubmissionEntity : BaseEntity() {

    @Column(name = "exam_id", nullable = false)
    var examId: UUID = UUID.randomUUID()

    @Column(name = "user_id", nullable = false)
    var userId: UUID = UUID.randomUUID()

    @Column(nullable = false)
    var score: Int = 0

    @Column(name = "total_points", nullable = false)
    var totalPoints: Int = 0

    @Column(nullable = false)
    var percentage: Int = 0

    @Column(length = 16)
    var grade: String? = null

    @Column(name = "correct_count", nullable = false)
    var correctCount: Int = 0

    @Column(name = "question_count", nullable = false)
    var questionCount: Int = 0

    @Column(name = "time_taken_seconds", nullable = false)
    var timeTakenSeconds: Int = 0

    @Column(name = "submitted_at", nullable = false)
    var submittedAt: Instant = Instant.now()

    /** JSON object string of the raw submitted answers. */
    @Column(columnDefinition = "text")
    var answers: String? = null

    /** JSON array string of per-question grading details. */
    @Column(name = "question_results", columnDefinition = "text")
    var questionResults: String? = null
}
