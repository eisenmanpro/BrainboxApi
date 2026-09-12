package com.afrithecus.brainbox.api.announcement.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/** Per-student read/acknowledgement state for an announcement. */
@Entity
@Table(name = "announcement_views")
class AnnouncementViewEntity {

    @Id
    @Column(nullable = false, updatable = false)
    var id: UUID = UUID.randomUUID()

    @Column(name = "announcement_id", nullable = false)
    var announcementId: UUID = UUID.randomUUID()

    @Column(name = "student_id", nullable = false)
    var studentId: UUID = UUID.randomUUID()

    @Column(name = "viewed_at")
    var viewedAt: Instant? = null

    @Column(name = "acknowledged_at")
    var acknowledgedAt: Instant? = null
}
