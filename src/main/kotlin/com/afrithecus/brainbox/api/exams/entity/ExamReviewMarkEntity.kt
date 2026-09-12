package com.afrithecus.brainbox.api.exams.entity

import com.afrithecus.brainbox.api.common.jpa.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * A teacher's mark for one reviewable question of a submission. The
 * (submissionId, questionId) pair is the idempotency key; re-marking replaces
 * the previous mark rather than adding to it.
 */
@Entity
@Table(name = "exam_review_marks")
class ExamReviewMarkEntity : BaseEntity() {

    @Column(name = "submission_id", nullable = false)
    var submissionId: UUID = UUID.randomUUID()

    @Column(name = "question_id", nullable = false)
    var questionId: UUID = UUID.randomUUID()

    @Column(nullable = false)
    var mark: Int = 0

    @Column(name = "reviewed_by", nullable = false)
    var reviewedBy: UUID = UUID.randomUUID()

    @Column(name = "reviewed_at", nullable = false)
    var reviewedAt: Instant = Instant.now()
}
