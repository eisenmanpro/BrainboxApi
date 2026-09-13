package com.afrithecus.brainbox.api.feedback.entity

import com.afrithecus.brainbox.api.common.jpa.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.util.UUID

/** Feedback a teacher leaves for a submission (doc 04 section 7.2/7.3). */
@Entity
@Table(name = "teacher_feedback")
class TeacherFeedbackEntity : BaseEntity() {

    @Column(name = "client_id", nullable = false, length = 80)
    var clientId: String = ""

    @Column(name = "teacher_id", nullable = false)
    var teacherId: UUID = UUID.randomUUID()

    @Column(name = "student_id", nullable = false)
    var studentId: UUID = UUID.randomUUID()

    @Column(name = "submission_id", length = 120)
    var submissionId: String? = null

    @Column(name = "text_feedback", columnDefinition = "text")
    var textFeedback: String? = null

    @Column(name = "voice_feedback_url", length = 512)
    var voiceFeedbackUrl: String? = null

    /** JSON array string of photo urls. */
    @Column(name = "photo_feedback_urls", columnDefinition = "text")
    var photoFeedbackUrls: String? = null

    /** JSON object string of criterion -> score. */
    @Column(name = "rubric_scores", columnDefinition = "text")
    var rubricScores: String? = null
}
