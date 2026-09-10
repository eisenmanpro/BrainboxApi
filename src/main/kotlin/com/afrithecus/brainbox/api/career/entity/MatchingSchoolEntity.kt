package com.afrithecus.brainbox.api.career.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/** Senior-school option used by the school matching engine (doc 06 §4). */
@Entity
@Table(name = "matching_schools")
class MatchingSchoolEntity {

    @Id
    @Column(nullable = false, updatable = false)
    var id: UUID = UUID.randomUUID()

    @Column(nullable = false, length = 200)
    var name: String = ""

    @Column(nullable = false, length = 160)
    var location: String = ""

    @Column(nullable = false, length = 32)
    var type: String = ""

    @Column(nullable = false, length = 8)
    var cluster: String = ""

    /** JSON array string of CbcPathway names. */
    @Column(columnDefinition = "text")
    var pathways: String? = null

    @Column(nullable = false)
    var slots: Int = 0

    @Column(name = "match_reason", nullable = false, length = 255)
    var matchReason: String = ""

    @Column(name = "required_grade", length = 64)
    var requiredGrade: String? = null

    @Column(name = "required_points")
    var requiredPoints: Int? = null

    @Column(nullable = false)
    var rating: Double = 4.0

    @Column(length = 512)
    var website: String? = null

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant = Instant.now()
}
