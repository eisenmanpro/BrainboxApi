package com.afrithecus.brainbox.api.timetable.entity

import com.afrithecus.brainbox.api.common.jpa.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.util.UUID

/** A small peer study circle a teacher convenes (doc 04 section 9.3). */
@Entity
@Table(name = "teacher_peer_circles")
class PeerCircleEntity : BaseEntity() {

    @Column(name = "client_id", nullable = false, length = 80)
    var clientId: String = ""

    @Column(name = "teacher_id", nullable = false)
    var teacherId: UUID = UUID.randomUUID()

    @Column(name = "school_id")
    var schoolId: UUID? = null

    @Column(name = "circle_name", nullable = false)
    var circleName: String = ""

    /** JSON array string of student ids. */
    @Column(name = "student_ids", columnDefinition = "text")
    var studentIds: String? = null

    @Column(name = "is_active", nullable = false)
    var isActive: Boolean = true
}
