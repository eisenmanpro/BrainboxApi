package com.afrithecus.brainbox.api.learning.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/** Append-only reading session (doc 03 §3.3). */
@Entity
@Table(name = "learning_reading_sessions")
class ReadingSessionEntity {

    @Id
    @Column(nullable = false, updatable = false)
    var id: UUID = UUID.randomUUID()

    @Column(name = "file_id", nullable = false)
    var fileId: UUID = UUID.randomUUID()

    @Column(name = "user_id", nullable = false)
    var userId: UUID = UUID.randomUUID()

    @Column(name = "start_time", nullable = false)
    var startTime: Instant = Instant.now()

    @Column(name = "end_time", nullable = false)
    var endTime: Instant = Instant.now()

    @Column(name = "pages_read", nullable = false)
    var pagesRead: Int = 0

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant = Instant.now()
}
