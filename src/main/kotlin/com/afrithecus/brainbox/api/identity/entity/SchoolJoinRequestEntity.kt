package com.afrithecus.brainbox.api.identity.entity

import com.afrithecus.brainbox.api.common.jpa.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.util.UUID

/** A student's request to join a school (moderation queue). */
@Entity
@Table(name = "school_join_requests")
class SchoolJoinRequestEntity : BaseEntity() {

    @Column(name = "school_id", nullable = false)
    var schoolId: UUID = UUID.randomUUID()

    @Column(name = "student_id", nullable = false)
    var studentId: UUID = UUID.randomUUID()

    @Column(name = "student_name", nullable = false, length = 160)
    var studentName: String = ""

    @Column(name = "grade_level", nullable = false, length = 64)
    var gradeLevel: String = ""

    @Column(name = "admission_number", length = 64)
    var admissionNumber: String? = null

    @Column(nullable = false, length = 16)
    var status: String = "PENDING"

    @Column(nullable = false, length = 32)
    var reference: String = ""
}
