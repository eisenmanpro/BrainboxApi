package com.afrithecus.brainbox.api.timetable.entity

import com.afrithecus.brainbox.api.common.jpa.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.util.UUID

/** A house group (student team) allocated to a teacher's class (doc 04 section 9.3). */
@Entity
@Table(name = "teacher_house_groups")
class HouseGroupEntity : BaseEntity() {

    @Column(name = "client_id", nullable = false, length = 80)
    var clientId: String = ""

    @Column(name = "teacher_id", nullable = false)
    var teacherId: UUID = UUID.randomUUID()

    @Column(name = "school_id")
    var schoolId: UUID? = null

    @Column(name = "house_id", length = 80)
    var houseId: String? = null

    @Column(name = "house_name", nullable = false)
    var houseName: String = ""

    @Column(name = "house_color", length = 16)
    var houseColor: String? = null

    @Column(name = "class_id", length = 80)
    var classId: String? = null

    @Column(name = "member_count", nullable = false)
    var memberCount: Int = 0

    /** JSON array string of student ids. */
    @Column(name = "student_ids", columnDefinition = "text")
    var studentIds: String? = null

    @Column(name = "is_active", nullable = false)
    var isActive: Boolean = true
}
