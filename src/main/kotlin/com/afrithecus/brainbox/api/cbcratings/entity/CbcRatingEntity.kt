package com.afrithecus.brainbox.api.cbcratings.entity

import com.afrithecus.brainbox.api.common.jpa.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/** A teacher's CBC rating for one learner and strand in a term. */
@Entity
@Table(name = "cbc_ratings")
class CbcRatingEntity : BaseEntity() {

    @Column(name = "student_id", nullable = false)
    var studentId: UUID = UUID.randomUUID()

    @Column(name = "teacher_id", nullable = false)
    var teacherId: UUID = UUID.randomUUID()

    @Column(name = "strand_code", nullable = false, length = 32)
    var strandCode: String = ""

    @Column(nullable = false, length = 32)
    var term: String = ""

    @Column(nullable = false, length = 16)
    var rating: String = "MEETING"

    @Column(length = 512)
    var evidence: String? = null

    @Column(columnDefinition = "text")
    var comments: String? = null

    @Column(name = "rated_at", nullable = false)
    var ratedAt: Instant = Instant.now()
}
