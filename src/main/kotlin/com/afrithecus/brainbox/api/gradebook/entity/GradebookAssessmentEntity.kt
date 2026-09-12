package com.afrithecus.brainbox.api.gradebook.entity

import com.afrithecus.brainbox.api.common.jpa.BaseEntity
import com.afrithecus.brainbox.api.traditional.model.ExamTerm
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/** A teacher-authored gradebook assessment (docs/ongoing/api_gradebook_changes.md section 2). */
@Entity
@Table(name = "gradebook_assessments")
class GradebookAssessmentEntity : BaseEntity() {

    /** The client-supplied id (assess_<uuid>), used for idempotent upserts. */
    @Column(name = "client_id", nullable = false, length = 80)
    var clientId: String = ""

    @Column(name = "class_id", nullable = false)
    var classId: UUID = UUID.randomUUID()

    @Column(nullable = false, length = 200)
    var title: String = ""

    @Column(name = "assessment_type", nullable = false, length = 16)
    var assessmentType: String = "QUIZ"

    @Column(name = "max_score", nullable = false)
    var maxScore: Int = 100

    @Column(name = "date_assigned", nullable = false)
    var dateAssigned: Instant = Instant.now()

    @Column(name = "cbc_strand_tag", length = 128)
    var cbcStrandTag: String? = null

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    var term: ExamTerm = ExamTerm.TERM_1

    @Column(name = "is_published", nullable = false)
    var isPublished: Boolean = false

    @Column(name = "counts_toward_average", nullable = false)
    var countsTowardAverage: Boolean = true

    @Column(name = "created_by", nullable = false)
    var createdBy: UUID = UUID.randomUUID()

    @Column(name = "school_id")
    var schoolId: UUID? = null
}
