package com.afrithecus.brainbox.api.traditional.entity

import com.afrithecus.brainbox.api.traditional.model.TraditionalSubjectType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.util.UUID

/** Coordinator-configured subject catalogue for a grade (doc 10 §8). */
@Entity
@Table(name = "traditional_subject_configs")
class TraditionalSubjectConfigEntity {

    @Id
    @Column(nullable = false, updatable = false)
    var id: UUID = UUID.randomUUID()

    @Column(name = "school_id")
    var schoolId: UUID? = null

    @Column(name = "grade_level", nullable = false, length = 64)
    var gradeLevel: String = ""

    @Column(name = "subject_id", nullable = false, length = 64)
    var subjectId: String = ""

    @Column(nullable = false, length = 128)
    var name: String = ""

    @Column(name = "max_score", nullable = false)
    var maxScore: Int = 100

    @Enumerated(EnumType.STRING)
    @Column(name = "subject_type", nullable = false, length = 16)
    var subjectType: TraditionalSubjectType = TraditionalSubjectType.SINGLE

    @Column(name = "is_optional", nullable = false)
    var isOptional: Boolean = false

    @Column(columnDefinition = "text")
    var components: String? = null

    @Column(name = "order_index", nullable = false)
    var orderIndex: Int = 0
}
