package com.afrithecus.brainbox.api.classes.entity

import com.afrithecus.brainbox.api.common.jpa.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.util.UUID

/** A class owned by a teacher (doc 04 §2.2). */
@Entity
@Table(name = "teacher_classes")
class TeacherClassEntity : BaseEntity() {

    @Column(name = "teacher_user_id", nullable = false)
    var teacherUserId: UUID = UUID.randomUUID()

    @Column(name = "school_id")
    var schoolId: UUID? = null

    @Column(nullable = false)
    var name: String = ""

    @Column(name = "grade_level", nullable = false, length = 64)
    var gradeLevel: String = ""

    @Column(nullable = false, length = 128)
    var subject: String = ""

    @Column(name = "is_active", nullable = false)
    var isActive: Boolean = true
}
