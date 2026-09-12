package com.afrithecus.brainbox.api.exams.entity

import com.afrithecus.brainbox.api.common.jpa.BaseEntity
import com.afrithecus.brainbox.api.exams.model.ExamScope
import com.afrithecus.brainbox.api.exams.model.ExamStatus
import com.afrithecus.brainbox.api.exams.model.ExamType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/** An authored exam (doc 02 §2.4). Answer keys live in its questions, never in student payloads. */
@Entity
@Table(name = "exams")
class ExamEntity : BaseEntity() {

    @Column(nullable = false)
    var title: String = ""

    @Column(nullable = false, length = 128)
    var subject: String = ""

    @Enumerated(EnumType.STRING)
    @Column(name = "exam_type", nullable = false, length = 32)
    var examType: ExamType = ExamType.DIGITAL

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    var scope: ExamScope = ExamScope.GLOBAL

    @Column(name = "school_id")
    var schoolId: UUID? = null

    @Column(name = "duration_minutes", nullable = false)
    var durationMinutes: Int = 0

    @Column(name = "question_count", nullable = false)
    var questionCount: Int = 0

    @Column(nullable = false)
    var difficulty: Int = 3

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    var status: ExamStatus = ExamStatus.DRAFT

    @Column(name = "exam_year")
    var examYear: Int? = null

    @Column(name = "is_mcp", nullable = false)
    var isMcp: Boolean = false

    @Column(name = "cover_image_url", length = 512)
    var coverImageUrl: String? = null

    @Column(name = "created_by", nullable = false)
    var createdBy: UUID = UUID.randomUUID()

    /** Client-supplied id (teacher authoring); absent for admin-authored exams. */
    @Column(name = "client_id", length = 80)
    var clientId: String? = null

    /** Class this exam was authored for, when the teacher scoped it to one. */
    @Column(name = "class_id")
    var classId: UUID? = null

    @Column(name = "grade_level")
    var gradeLevel: Int? = null

    @Column(name = "total_points", nullable = false)
    var totalPoints: Int = 0

    /** Academic term (TERM_1/TERM_2/TERM_3) the gradebook term filter reads. */
    @Column(length = 16)
    var term: String? = null

    @Column(name = "open_at")
    var openAt: Instant? = null

    @Column(name = "close_at")
    var closeAt: Instant? = null
}
