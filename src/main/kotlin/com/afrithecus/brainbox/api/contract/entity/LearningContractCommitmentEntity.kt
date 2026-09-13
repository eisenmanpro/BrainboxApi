package com.afrithecus.brainbox.api.contract.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/** One party's commitment inside a learning contract (doc 04 section 11.2). */
@Entity
@Table(name = "learning_contract_commitments")
class LearningContractCommitmentEntity {

    @Id
    @Column(nullable = false, updatable = false)
    var id: UUID = UUID.randomUUID()

    @Column(name = "client_id", nullable = false, length = 80)
    var clientId: String = ""

    @Column(name = "contract_id", nullable = false)
    var contractId: UUID = UUID.randomUUID()

    @Column(nullable = false, length = 16)
    var party: String = "STUDENT"

    @Column(nullable = false, columnDefinition = "text")
    var text: String = ""

    @Column(name = "is_completed", nullable = false)
    var isCompleted: Boolean = false

    @Column(name = "due_date")
    var dueDate: Instant? = null

    @Column(columnDefinition = "text")
    var notes: String? = null

    @Column(name = "completion_date")
    var completionDate: Instant? = null

    @Column(name = "sort_order", nullable = false)
    var sortOrder: Int = 0
}
