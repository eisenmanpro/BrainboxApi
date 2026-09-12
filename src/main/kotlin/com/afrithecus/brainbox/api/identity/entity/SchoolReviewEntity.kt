package com.afrithecus.brainbox.api.identity.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** A published school review; the author is server-derived from the session. */
@Entity
@Table(name = "school_reviews")
class SchoolReviewEntity {

    @Id
    @Column(nullable = false, updatable = false)
    var id: UUID = UUID.randomUUID()

    @Column(name = "school_id", nullable = false)
    var schoolId: UUID = UUID.randomUUID()

    @Column(name = "user_id", nullable = false)
    var userId: UUID = UUID.randomUUID()

    @Column(name = "user_name", nullable = false, length = 160)
    var userName: String = ""

    @Column(nullable = false)
    var rating: Int = 5

    @Column(nullable = false, columnDefinition = "text")
    var comment: String = ""

    @Column(name = "review_date", nullable = false)
    var reviewDate: LocalDate = LocalDate.now()

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant = Instant.now()
}
