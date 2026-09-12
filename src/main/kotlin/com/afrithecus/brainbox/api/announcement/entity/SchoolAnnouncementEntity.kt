package com.afrithecus.brainbox.api.announcement.entity

import com.afrithecus.brainbox.api.common.jpa.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/** A school-wide announcement authored by an admin (docs/ongoing/open_gaps.md ANN-1). */
@Entity
@Table(name = "school_announcements")
class SchoolAnnouncementEntity : BaseEntity() {

    @Column(name = "school_id", nullable = false)
    var schoolId: UUID = UUID.randomUUID()

    @Column(nullable = false, length = 200)
    var title: String = ""

    @Column(nullable = false, columnDefinition = "text")
    var body: String = ""

    @Column(nullable = false, length = 120)
    var audience: String = ""

    @Column(name = "posted_by")
    var postedBy: UUID? = null

    @Column(name = "posted_by_name", length = 160)
    var postedByName: String? = null

    @Column(name = "posted_at", nullable = false)
    var postedAt: Instant = Instant.now()

    @Column(name = "scheduled_meeting_date")
    var scheduledMeetingDate: Instant? = null

    @Column(name = "meeting_title", length = 200)
    var meetingTitle: String? = null
}
