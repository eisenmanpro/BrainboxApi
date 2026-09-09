package com.afrithecus.brainbox.api.classes.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/** Student membership in a class (roster). */
@Entity
@Table(name = "class_memberships")
class ClassMembershipEntity {

    @Id
    @Column(nullable = false, updatable = false)
    var id: UUID = UUID.randomUUID()

    @Column(name = "class_id", nullable = false)
    var classId: UUID = UUID.randomUUID()

    @Column(name = "student_id", nullable = false)
    var studentId: UUID = UUID.randomUUID()

    @Column(name = "joined_at", nullable = false, updatable = false)
    var joinedAt: Instant = Instant.now()
}
