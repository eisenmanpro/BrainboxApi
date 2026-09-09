package com.afrithecus.brainbox.api.homework.entity

import com.afrithecus.brainbox.api.homework.model.GradingMode
import com.afrithecus.brainbox.api.homework.model.HomeworkScope
import com.afrithecus.brainbox.api.homework.model.SubmissionType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * A homework assignment. The primary key is the client-generated id
 * (hw_<epochMs>) so offline creates upsert idempotently (never regenerated).
 */
@Entity
@Table(name = "homework")
class HomeworkEntity {

    @Id
    @Column(nullable = false, length = 128)
    var id: String = ""

    @Column(name = "class_id", nullable = false)
    var classId: UUID = UUID.randomUUID()

    @Column(name = "teacher_id", nullable = false)
    var teacherId: UUID = UUID.randomUUID()

    @Column(name = "teacher_name", nullable = false)
    var teacherName: String = ""

    @Column(name = "school_id")
    var schoolId: UUID? = null

    @Column(nullable = false)
    var title: String = ""

    @Column(nullable = false)
    var description: String = ""

    @Column(nullable = false, length = 128)
    var subject: String = ""

    @Column(name = "grade_level", nullable = false)
    var gradeLevel: Int = 0

    @Column(name = "due_date", nullable = false)
    var dueDate: Instant = Instant.now()

    @Enumerated(EnumType.STRING)
    @Column(name = "submission_type", nullable = false, length = 32)
    var submissionType: SubmissionType = SubmissionType.FREE_TEXT

    /** JSON array string of checklist items. */
    @Column(name = "checklist_items", columnDefinition = "text")
    var checklistItems: String? = null

    @Enumerated(EnumType.STRING)
    @Column(name = "grading_mode", length = 32)
    var gradingMode: GradingMode? = null

    @Column(name = "is_past_paper_unlocked", nullable = false)
    var isPastPaperUnlocked: Boolean = false

    @Column(name = "cbc_strand_tag", length = 64)
    var cbcStrandTag: String? = null

    @Column(name = "cbc_sub_strand_tag", length = 60)
    var cbcSubStrandTag: String? = null

    /** JSON array string of targeted student ids; empty means whole class roster. */
    @Column(name = "assigned_student_ids", columnDefinition = "text")
    var assignedStudentIds: String? = null

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    var scope: HomeworkScope = HomeworkScope.SCHOOL_GRADE_CLASS

    @Column(name = "is_active", nullable = false)
    var isActive: Boolean = true

    @Column(name = "is_draft", nullable = false)
    var isDraft: Boolean = false

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant = Instant.now()

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now()
}
