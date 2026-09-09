package com.afrithecus.brainbox.api.homework.entity

import com.afrithecus.brainbox.api.homework.model.SubmissionStatus
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/** A student's submission for one homework. */
@Entity
@Table(name = "homework_submissions")
class HomeworkSubmissionEntity {

    @Id
    @Column(nullable = false, updatable = false)
    var id: UUID = UUID.randomUUID()

    @Column(name = "homework_id", nullable = false, length = 128)
    var homeworkId: String = ""

    @Column(name = "student_id", nullable = false)
    var studentId: UUID = UUID.randomUUID()

    @Column(name = "submission_text", columnDefinition = "text")
    var submissionText: String? = null

    /** JSON array string of checked item indices (CHECKLIST). */
    @Column(name = "checklist_answers", columnDefinition = "text")
    var checklistAnswers: String? = null

    @Column(name = "attachment_url", length = 512)
    var attachmentUrl: String? = null

    /** JSON object of question answers (EXAM_QUESTION_SET). */
    @Column(name = "answers_json", columnDefinition = "text")
    var answersJson: String? = null

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    var status: SubmissionStatus = SubmissionStatus.PENDING

    @Column(name = "submitted_at", nullable = false)
    var submittedAt: Instant = Instant.now()

    @Column(nullable = false)
    var grade: Int? = null

    @Column(columnDefinition = "text")
    var feedback: String? = null

    @Column(name = "cbc_strand_tag", length = 64)
    var cbcStrandTag: String? = null

    @Column(name = "graded_by")
    var gradedBy: UUID? = null

    @Column(name = "graded_at")
    var gradedAt: Instant? = null

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant = Instant.now()

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now()
}
