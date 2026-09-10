package com.afrithecus.brainbox.api.live.entity

import com.afrithecus.brainbox.api.common.jpa.BaseEntity
import com.afrithecus.brainbox.api.live.model.LiveClassStatus
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/** A scheduled/live/recorded class (doc 05 §4.2). */
@Entity
@Table(name = "live_classes")
class LiveClassEntity : BaseEntity() {

    @Column(name = "teacher_id", nullable = false)
    var teacherId: UUID = UUID.randomUUID()

    @Column(name = "teacher_name", nullable = false, length = 160)
    var teacherName: String = ""

    @Column(name = "school_id")
    var schoolId: UUID? = null

    @Column(nullable = false, length = 220)
    var title: String = ""

    @Column(nullable = false, length = 128)
    var subject: String = ""

    @Column(nullable = false, columnDefinition = "text")
    var description: String = ""

    @Column(name = "scheduled_start", nullable = false)
    var scheduledStart: Instant = Instant.now()

    @Column(name = "scheduled_end", nullable = false)
    var scheduledEnd: Instant = Instant.now()

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    var status: LiveClassStatus = LiveClassStatus.SCHEDULED

    @Column(name = "join_url", length = 512)
    var joinUrl: String? = null

    @Column(name = "recording_url", length = 512)
    var recordingUrl: String? = null

    @Column(name = "max_participants", nullable = false)
    var maxParticipants: Int = 100

    @Column(name = "thumbnail_url", length = 512)
    var thumbnailUrl: String? = null

    /** JSON array string of {name,url} materials. */
    @Column(columnDefinition = "text")
    var materials: String? = null
}
