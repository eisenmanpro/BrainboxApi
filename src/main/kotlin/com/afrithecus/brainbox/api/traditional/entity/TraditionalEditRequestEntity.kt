package com.afrithecus.brainbox.api.traditional.entity

import com.afrithecus.brainbox.api.common.jpa.BaseEntity
import com.afrithecus.brainbox.api.traditional.model.TraditionalEditStatus
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/** A teacher's request to change a confirmed mark (doc 10 §5). */
@Entity
@Table(name = "traditional_edit_requests")
class TraditionalEditRequestEntity : BaseEntity() {

    @Column(name = "exam_id", nullable = false)
    var examId: UUID = UUID.randomUUID()

    @Column(name = "requester_id", nullable = false)
    var requesterId: UUID = UUID.randomUUID()

    @Column(name = "student_id", nullable = false)
    var studentId: UUID = UUID.randomUUID()

    @Column(name = "subject_id", nullable = false, length = 64)
    var subjectId: String = ""

    @Column(name = "old_score", nullable = false)
    var oldScore: Int = 0

    @Column(name = "new_score", nullable = false)
    var newScore: Int = 0

    @Column(nullable = false, columnDefinition = "text")
    var reason: String = ""

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    var status: TraditionalEditStatus = TraditionalEditStatus.PENDING

    @Column(name = "coordinator_comment", columnDefinition = "text")
    var coordinatorComment: String? = null

    @Column(name = "reviewed_by")
    var reviewedBy: UUID? = null

    @Column(name = "reviewed_at")
    var reviewedAt: Instant? = null
}
