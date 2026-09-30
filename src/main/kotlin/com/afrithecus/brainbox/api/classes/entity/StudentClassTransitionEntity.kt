package com.afrithecus.brainbox.api.classes.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * Audit row for one learner move (docs/ongoing/product_ops_roadmap.md item 3).
 * Grade changes are yearly and can be arbitrary, so every move is recorded with
 * who did it and why — the record is what makes a move safe to make.
 */
@Entity
@Table(name = "student_class_transitions")
class StudentClassTransitionEntity {

    @Id
    @Column(nullable = false, updatable = false)
    var id: UUID = UUID.randomUUID()

    @Column(name = "student_id", nullable = false)
    var studentId: UUID = UUID.randomUUID()

    /** The class left, when the clean-up removed exactly one; null for a join-only move. */
    @Column(name = "from_class_id")
    var fromClassId: UUID? = null

    @Column(name = "to_class_id", nullable = false)
    var toClassId: UUID = UUID.randomUUID()

    /** PULL, PUSH or PROMOTE. */
    @Column(nullable = false, length = 8)
    var mode: String = "PUSH"

    @Column(length = 500)
    var reason: String? = null

    @Column(name = "initiated_by", nullable = false)
    var initiatedBy: UUID = UUID.randomUUID()

    @Column(name = "school_id")
    var schoolId: UUID? = null

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant = Instant.now()
}
