package com.afrithecus.brainbox.api.contests.entity

import com.afrithecus.brainbox.api.common.jpa.BaseEntity
import com.afrithecus.brainbox.api.contests.model.ContestLifecycle
import com.afrithecus.brainbox.api.contests.model.ContestScope
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/** A timed competition (doc 05 §1.1). */
@Entity
@Table(name = "contests")
class ContestEntity : BaseEntity() {

    @Column(nullable = false)
    var title: String = ""

    @Column(nullable = false, length = 128)
    var subject: String = ""

    @Column(nullable = false, length = 64)
    var grade: String = ""

    @Column(name = "start_time", nullable = false)
    var startTime: Instant = Instant.now()

    @Column(name = "end_time", nullable = false)
    var endTime: Instant = Instant.now()

    @Column(name = "entry_fee", nullable = false)
    var entryFee: Int = 0

    @Column(length = 255)
    var prize: String? = null

    @Column(name = "max_participants")
    var maxParticipants: Int? = null

    @Column(nullable = false)
    var difficulty: Int = 3

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    var lifecycle: ContestLifecycle = ContestLifecycle.PUBLISHED

    @Column(name = "created_by", nullable = false)
    var createdBy: UUID = UUID.randomUUID()

    /** Visibility; see ContestScope. Teacher-authored contests are SCHOOL_GRADE. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    var scope: ContestScope = ContestScope.GLOBAL

    /** The author's school for SCHOOL/SCHOOL_GRADE; null for a global BrainBox contest. */
    @Column(name = "school_id")
    var schoolId: UUID? = null

    /** Optional teacher-supplied rules; the app shows its generic rules when null. */
    @Column(columnDefinition = "text")
    var rules: String? = null

    /** Set when the author closes the window early; results become publishable. */
    @Column(name = "finalised_at")
    var finalisedAt: Instant? = null

    /** Set by publish-results; the learner notice is emitted here, not inferred client-side. */
    @Column(name = "results_published_at")
    var resultsPublishedAt: Instant? = null
}
