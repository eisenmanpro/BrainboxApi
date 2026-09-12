package com.afrithecus.brainbox.api.exams.entity

import com.afrithecus.brainbox.api.common.jpa.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/** A class-wide remediation action assigned to a weak CBC strand of an exam. */
@Entity
@Table(name = "exam_remediations")
class ExamRemediationEntity : BaseEntity() {

    @Column(name = "exam_id", nullable = false)
    var examId: UUID = UUID.randomUUID()

    @Column(name = "cbc_strand", nullable = false, length = 128)
    var cbcStrand: String = ""

    @Column(nullable = false, length = 32)
    var action: String = ""

    @Column(name = "assigned_by", nullable = false)
    var assignedBy: UUID = UUID.randomUUID()

    @Column(name = "assigned_at", nullable = false)
    var assignedAt: Instant = Instant.now()
}
