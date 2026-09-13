package com.afrithecus.brainbox.api.content.entity

import com.afrithecus.brainbox.api.common.jpa.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.util.UUID

/** Maps a concept onto a national curriculum strand/substrand and grade. */
@Entity
@Table(name = "curriculum_map")
class CurriculumMapEntity : BaseEntity() {

    @Column(name = "concept_id", nullable = false)
    var conceptId: UUID = UUID.randomUUID()

    @Column(name = "country_code", nullable = false, length = 8)
    var countryCode: String = ""

    @Column(nullable = false, length = 32)
    var curriculum: String = ""

    @Column(name = "grade_level", nullable = false, length = 32)
    var gradeLevel: String = ""

    @Column(name = "strand_code", length = 64)
    var strandCode: String? = null

    @Column(name = "strand_name", length = 160)
    var strandName: String? = null

    @Column(name = "substrand_code", length = 64)
    var substrandCode: String? = null

    @Column(name = "substrand_name", length = 160)
    var substrandName: String? = null

    @Column(name = "learning_outcome", columnDefinition = "text")
    var learningOutcome: String? = null

    @Column(name = "sort_order", nullable = false)
    var sortOrder: Int = 0
}
