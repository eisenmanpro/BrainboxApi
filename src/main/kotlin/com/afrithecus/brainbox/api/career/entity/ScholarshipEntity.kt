package com.afrithecus.brainbox.api.career.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/** Reference scholarship opportunity (doc 06 §1.2). */
@Entity
@Table(name = "scholarships")
class ScholarshipEntity {

    @Id
    @Column(nullable = false, updatable = false)
    var id: UUID = UUID.randomUUID()

    @Column(nullable = false, length = 200)
    var title: String = ""

    @Column(nullable = false, length = 160)
    var provider: String = ""

    @Column(nullable = false, length = 120)
    var amount: String = ""

    @Column(nullable = false)
    var deadline: Instant = Instant.now()

    @Column(name = "external_url", nullable = false, length = 512)
    var externalUrl: String = ""

    @Column(name = "eligibility_label", nullable = false, length = 200)
    var eligibilityLabel: String = ""

    @Column(name = "thumbnail_url", length = 512)
    var thumbnailUrl: String? = null

    /** JSON array string of CbcGradeBand names. */
    @Column(name = "grade_bands", columnDefinition = "text")
    var gradeBands: String? = null

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant = Instant.now()
}
