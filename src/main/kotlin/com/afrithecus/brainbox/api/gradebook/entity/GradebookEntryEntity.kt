package com.afrithecus.brainbox.api.gradebook.entity

import com.afrithecus.brainbox.api.common.jpa.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/** One manual grade for a student against an assessment (api_gradebook_changes.md section 1). */
@Entity
@Table(name = "gradebook_entries")
class GradebookEntryEntity : BaseEntity() {

    @Column(name = "client_id", nullable = false, length = 80)
    var clientId: String = ""

    @Column(name = "class_id", nullable = false)
    var classId: UUID = UUID.randomUUID()

    /** The client id of the owning assessment. */
    @Column(name = "assessment_id", nullable = false, length = 80)
    var assessmentId: String = ""

    @Column(name = "teacher_id", nullable = false)
    var teacherId: UUID = UUID.randomUUID()

    @Column(name = "student_id", nullable = false)
    var studentId: UUID = UUID.randomUUID()

    @Column(name = "student_name", nullable = false, length = 160)
    var studentName: String = ""

    @Column(name = "assessment_type", nullable = false, length = 16)
    var assessmentType: String = "QUIZ"

    @Column(name = "assessment_title", length = 200)
    var assessmentTitle: String? = null

    @Column(name = "raw_score", nullable = false)
    var rawScore: Int = 0

    @Column(name = "max_score", nullable = false)
    var maxScore: Int = 0

    @Column(nullable = false)
    var percentage: Int = 0

    @Column(name = "cbc_strand_tag", length = 128)
    var cbcStrandTag: String? = null

    @Column(name = "teacher_note", columnDefinition = "text")
    var teacherNote: String? = null

    @Column(name = "graded_at", nullable = false)
    var gradedAt: Instant = Instant.now()
}
