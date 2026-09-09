package com.afrithecus.brainbox.api.identity.entity

import com.afrithecus.brainbox.api.common.jpa.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.util.UUID

/** Teaching details for a TEACHER user (doc 01 §7.2). */
@Entity
@Table(name = "teacher_profiles")
class TeacherProfileEntity : BaseEntity() {

    @Column(name = "user_id", nullable = false)
    var userId: UUID = UUID.randomUUID()

    @Column(name = "school_id")
    var schoolId: UUID? = null

    @Column(length = 128)
    var subject: String? = null
}
