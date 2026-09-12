package com.afrithecus.brainbox.api.traditional.entity

import com.afrithecus.brainbox.api.common.jpa.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/** One student's mark for one subject in one traditional exam (doc 10 §3.1). */
@Entity
@Table(name = "traditional_marks")
class TraditionalMarkEntity : BaseEntity() {

    @Column(name = "exam_id", nullable = false)
    var examId: UUID = UUID.randomUUID()

    @Column(name = "student_id", nullable = false)
    var studentId: UUID = UUID.randomUUID()

    @Column(name = "subject_id", nullable = false, length = 64)
    var subjectId: String = ""

    @Column(name = "raw_score", nullable = false)
    var rawScore: Int = 0

    @Column
    var percentage: Double? = null

    @Column(name = "grade_band", length = 8)
    var gradeBand: String? = null

    /** JSON object of componentId -> raw score for combined subjects. */
    @Column(name = "component_scores", columnDefinition = "text")
    var componentScores: String? = null

    @Column(name = "confirmed_by_teacher", nullable = false)
    var confirmedByTeacher: Boolean = false

    @Column(name = "confirmed_at")
    var confirmedAt: Instant? = null

    @Column(name = "entered_by")
    var enteredBy: UUID? = null

    @Column(name = "entered_at", nullable = false)
    var enteredAt: Instant = Instant.now()
}
