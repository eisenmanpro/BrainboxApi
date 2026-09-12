package com.afrithecus.brainbox.api.classchat.entity

import com.afrithecus.brainbox.api.common.jpa.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/** A chat group scoped to one of the teacher's classes (doc 04 §12.1). */
@Entity
@Table(name = "class_groups")
class ClassGroupEntity : BaseEntity() {

    @Column(name = "class_id", nullable = false)
    var classId: UUID = UUID.randomUUID()

    @Column(name = "teacher_id", nullable = false)
    var teacherId: UUID = UUID.randomUUID()

    @Column(name = "teacher_name", nullable = false, length = 160)
    var teacherName: String = ""

    @Column(nullable = false, length = 200)
    var name: String = ""

    @Column(length = 500)
    var description: String? = null

    @Column(name = "is_announcement_mode", nullable = false)
    var isAnnouncementMode: Boolean = false

    @Column(name = "teacher_muted_until")
    var teacherMutedUntil: Instant? = null
}
