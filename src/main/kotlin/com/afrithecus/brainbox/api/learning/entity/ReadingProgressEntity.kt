package com.afrithecus.brainbox.api.learning.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/** Latest reading position for (user, file) (doc 03 §3.2). */
@Entity
@Table(name = "learning_reading_progress")
class ReadingProgressEntity {

    @Id
    @Column(nullable = false, updatable = false)
    var id: UUID = UUID.randomUUID()

    @Column(name = "file_id", nullable = false)
    var fileId: UUID = UUID.randomUUID()

    @Column(name = "user_id", nullable = false)
    var userId: UUID = UUID.randomUUID()

    @Column(name = "current_page", nullable = false)
    var currentPage: Int = 0

    @Column(name = "total_pages", nullable = false)
    var totalPages: Int = 0

    @Column(name = "last_read_at", nullable = false)
    var lastReadAt: Instant = Instant.now()

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now()
}
