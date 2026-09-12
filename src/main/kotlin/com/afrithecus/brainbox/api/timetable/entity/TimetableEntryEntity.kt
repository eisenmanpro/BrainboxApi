package com.afrithecus.brainbox.api.timetable.entity

import com.afrithecus.brainbox.api.common.jpa.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.util.UUID

/** A single slot on a teacher's weekly timetable (doc 04 section 9.1). */
@Entity
@Table(name = "teacher_timetable_entries")
class TimetableEntryEntity : BaseEntity() {

    /** Client-supplied id; makes offline create/update replay idempotent. */
    @Column(name = "client_id", nullable = false, length = 80)
    var clientId: String = ""

    @Column(name = "teacher_id", nullable = false)
    var teacherId: UUID = UUID.randomUUID()

    @Column(name = "school_id")
    var schoolId: UUID? = null

    @Column(name = "class_id", length = 80)
    var classId: String? = null

    @Column(name = "class_name", nullable = false)
    var className: String = ""

    @Column(nullable = false, length = 128)
    var subject: String = ""

    @Column(name = "day_of_week", nullable = false)
    var dayOfWeek: Int = 1

    @Column(name = "start_time", nullable = false, length = 8)
    var startTime: String = ""

    @Column(name = "end_time", nullable = false, length = 8)
    var endTime: String = ""

    @Column(name = "room_id", length = 80)
    var roomId: String? = null

    @Column(name = "room_name", length = 200)
    var roomName: String? = null

    @Column(name = "color_hex", length = 16)
    var colorHex: String? = null

    @Column(name = "house_id", length = 80)
    var houseId: String? = null

    @Column(name = "community_service_id", length = 80)
    var communityServiceId: String? = null

    @Column(name = "peer_circle_id", length = 80)
    var peerCircleId: String? = null

    @Column(name = "entry_type", nullable = false, length = 32)
    var entryType: String = "LECTURE"

    @Column(name = "practical_block_type", nullable = false, length = 32)
    var practicalBlockType: String = "NONE"
}
