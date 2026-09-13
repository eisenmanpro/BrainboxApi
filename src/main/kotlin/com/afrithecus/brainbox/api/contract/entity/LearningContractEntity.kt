package com.afrithecus.brainbox.api.contract.entity

import com.afrithecus.brainbox.api.common.jpa.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/** A teacher's learning contract with a learner (doc 04 section 11.1). */
@Entity
@Table(name = "learning_contracts")
class LearningContractEntity : BaseEntity() {

    @Column(name = "client_id", nullable = false, length = 80)
    var clientId: String = ""

    @Column(name = "teacher_id", nullable = false)
    var teacherId: UUID = UUID.randomUUID()

    @Column(name = "child_id", nullable = false)
    var childId: UUID = UUID.randomUUID()

    @Column(nullable = false, length = 32)
    var term: String = ""

    @Column(nullable = false, length = 16)
    var status: String = "ACTIVE"

    @Column(name = "start_date", nullable = false)
    var startDate: Instant = Instant.now()

    @Column(name = "end_date", nullable = false)
    var endDate: Instant = Instant.now()

    @Column(name = "last_updated", nullable = false)
    var lastUpdated: Instant = Instant.now()
}
