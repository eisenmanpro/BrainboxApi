package com.afrithecus.brainbox.api.cbcratings.entity

import com.afrithecus.brainbox.api.common.jpa.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.util.UUID

/** A CBC curriculum strand or sub-strand (doc 04 CBC analytics, Phase 7.5b-1). */
@Entity
@Table(name = "cbc_strands")
class CbcStrandEntity : BaseEntity() {

    @Column(nullable = false, length = 32)
    var code: String = ""

    @Column(nullable = false, length = 160)
    var name: String = ""

    @Column(nullable = false, columnDefinition = "text")
    var descriptor: String = ""

    @Column(name = "grade_level", nullable = false, length = 32)
    var gradeLevel: String = "ALL"

    @Column(nullable = false, length = 64)
    var subject: String = ""

    @Column(name = "sort_order", nullable = false)
    var sortOrder: Int = 0

    /** Parent strand id for a sub-strand; null for a top-level strand. */
    @Column(name = "parent_id")
    var parentId: UUID? = null

    /** STRAND or SUBSTRAND (the catalogue depth). */
    @Column(nullable = false, length = 16)
    var level: String = "STRAND"

    /** The curriculum_versions.version this row was authored against. */
    @Column(name = "curriculum_version", length = 32)
    var curriculumVersion: String? = null
}
