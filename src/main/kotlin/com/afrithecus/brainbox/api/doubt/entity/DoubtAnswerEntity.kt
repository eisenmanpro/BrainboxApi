package com.afrithecus.brainbox.api.doubt.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/** An answer to a doubt question (doc 05 §3.4). */
@Entity
@Table(name = "doubt_answers")
class DoubtAnswerEntity {

    @Id
    @Column(nullable = false, updatable = false)
    var id: UUID = UUID.randomUUID()

    @Column(name = "question_id", nullable = false)
    var questionId: UUID = UUID.randomUUID()

    @Column(nullable = false)
    var body: String = ""

    @Column(name = "author_id", nullable = false)
    var authorId: UUID = UUID.randomUUID()

    /** Snapshot at answer time: TEACHER or STUDENT (doc 05 §3.4). */
    @Column(name = "author_role", nullable = false, length = 16)
    var authorRole: String = "STUDENT"

    @Column(name = "is_accepted", nullable = false)
    var isAccepted: Boolean = false

    @Column(name = "vote_count", nullable = false)
    var voteCount: Int = 0

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant = Instant.now()

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now()
}
