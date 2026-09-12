package com.afrithecus.brainbox.api.traditional.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.util.UUID

/** Coordinator-configured grading bands for a grade (doc 10 §8). */
@Entity
@Table(name = "traditional_grading_configs")
class TraditionalGradingConfigEntity {

    @Id
    @Column(nullable = false, updatable = false)
    var id: UUID = UUID.randomUUID()

    @Column(name = "school_id")
    var schoolId: UUID? = null

    @Column(name = "grade_level", nullable = false, length = 64)
    var gradeLevel: String = ""

    /** JSON array of {grade,minPercentage}. */
    @Column(nullable = false, columnDefinition = "text")
    var bands: String = "[]"

    /** JSON array of {grade,minRawScore}; null falls back to the default bands. */
    @Column(name = "overall_bands", columnDefinition = "text")
    var overallBands: String? = null
}
