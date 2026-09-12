package com.afrithecus.brainbox.api.timetable.entity

import com.afrithecus.brainbox.api.common.jpa.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.util.UUID

/** A community service project with assigned students (doc 04 section 9.4). */
@Entity
@Table(name = "teacher_community_services")
class CommunityServiceEntity : BaseEntity() {

    @Column(name = "client_id", nullable = false, length = 80)
    var clientId: String = ""

    @Column(name = "teacher_id", nullable = false)
    var teacherId: UUID = UUID.randomUUID()

    @Column(name = "school_id")
    var schoolId: UUID? = null

    @Column(name = "service_name", nullable = false)
    var serviceName: String = ""

    /** JSON array string of student ids. */
    @Column(name = "students_assigned", columnDefinition = "text")
    var studentsAssigned: String? = null

    @Column(name = "is_active", nullable = false)
    var isActive: Boolean = true
}
