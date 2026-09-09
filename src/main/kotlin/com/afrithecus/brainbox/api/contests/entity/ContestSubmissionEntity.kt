package com.afrithecus.brainbox.api.contests.entity

import com.afrithecus.brainbox.api.common.jpa.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/** Server-graded contest result (doc 05 §1.6). */
@Entity
@Table(name = "contest_submissions")
class ContestSubmissionEntity : BaseEntity() {

    @Column(name = "contest_id", nullable = false)
    var contestId: UUID = UUID.randomUUID()

    @Column(name = "user_id", nullable = false)
    var userId: UUID = UUID.randomUUID()

    @Column(nullable = false)
    var score: Int = 0

    @Column(name = "total_points", nullable = false)
    var totalPoints: Int = 0

    @Column(nullable = false)
    var percentage: Int = 0

    @Column(name = "correct_count", nullable = false)
    var correctCount: Int = 0

    @Column(name = "question_count", nullable = false)
    var questionCount: Int = 0

    @Column(name = "submitted_at", nullable = false)
    var submittedAt: Instant = Instant.now()

    @Column(columnDefinition = "text")
    var answers: String? = null

    @Column(name = "question_results", columnDefinition = "text")
    var questionResults: String? = null
}
