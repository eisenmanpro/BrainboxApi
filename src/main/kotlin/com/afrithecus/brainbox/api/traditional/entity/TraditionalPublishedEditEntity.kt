package com.afrithecus.brainbox.api.traditional.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/** Permanent audit of a coordinator's direct score correction after publication. */
@Entity
@Table(name = "traditional_published_edits")
class TraditionalPublishedEditEntity {

    @Id
    @Column(nullable = false, updatable = false)
    var id: UUID = UUID.randomUUID()

    @Column(name = "exam_id", nullable = false)
    var examId: UUID = UUID.randomUUID()

    @Column(name = "student_id", nullable = false)
    var studentId: UUID = UUID.randomUUID()

    @Column(name = "actor_id", nullable = false)
    var actorId: UUID = UUID.randomUUID()

    @Column(name = "old_score", nullable = false)
    var oldScore: Int = 0

    @Column(name = "new_score", nullable = false)
    var newScore: Int = 0

    @Column(name = "edited_at", nullable = false)
    var editedAt: Instant = Instant.now()

    @Column(name = "notified_learner", nullable = false)
    var notifiedLearner: Boolean = false

    @Column(name = "notified_class_teacher", nullable = false)
    var notifiedClassTeacher: Boolean = false

    @Column(name = "notified_ict_admins", nullable = false)
    var notifiedIctAdmins: Boolean = false
}
