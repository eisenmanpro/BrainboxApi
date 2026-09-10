package com.afrithecus.brainbox.api.career.entity

import com.afrithecus.brainbox.api.common.jpa.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.util.UUID

/** Student request to be mentored; unique per (mentor, user). */
@Entity
@Table(name = "mentor_requests")
class MentorRequestEntity : BaseEntity() {

    @Column(name = "mentor_id", nullable = false)
    var mentorId: UUID = UUID.randomUUID()

    @Column(name = "user_id", nullable = false)
    var userId: UUID = UUID.randomUUID()

    @Column(nullable = false, length = 16)
    var status: String = "PENDING"
}
