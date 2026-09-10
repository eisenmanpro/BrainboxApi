package com.afrithecus.brainbox.api.career.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/** CBC elective subject catalog (doc 06 §1.2 / elective chooser). */
@Entity
@Table(name = "elective_subjects")
class ElectiveSubjectEntity {

    @Id
    @Column(nullable = false, updatable = false)
    var id: UUID = UUID.randomUUID()

    @Column(nullable = false, length = 160)
    var name: String = ""

    @Column(nullable = false, length = 64)
    var category: String = ""

    @Column(name = "is_core", nullable = false)
    var isCore: Boolean = false

    @Column(columnDefinition = "text")
    var description: String? = null

    /** JSON array string of CbcGradeBand names. */
    @Column(name = "grade_bands", columnDefinition = "text")
    var gradeBands: String? = null

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant = Instant.now()
}
