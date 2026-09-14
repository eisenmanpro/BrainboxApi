package com.afrithecus.brainbox.api.content.entity

import com.afrithecus.brainbox.api.common.jpa.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table

/**
 * A named, versioned national curriculum catalogue (Phase 7.5b-1). Content is
 * tagged against [version] so a band/pathway/subject change is a new row, not
 * an edit. The rows themselves are seeded from authored resource data by
 * CurriculumSeeder.
 */
@Entity
@Table(name = "curriculum_versions")
class CurriculumVersionEntity : BaseEntity() {

    @Column(name = "country_code", nullable = false, length = 8)
    var countryCode: String = ""

    @Column(nullable = false, length = 32)
    var curriculum: String = ""

    @Column(name = "curriculum_version", nullable = false, length = 32)
    var curriculumVersion: String = ""

    @Column(nullable = false, length = 200)
    var name: String = ""

    @Column(columnDefinition = "text")
    var notes: String? = null

    @Column(name = "is_active", nullable = false)
    var isActive: Boolean = false
}
