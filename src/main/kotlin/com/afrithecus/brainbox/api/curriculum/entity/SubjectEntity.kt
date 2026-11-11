package com.afrithecus.brainbox.api.curriculum.entity

import com.afrithecus.brainbox.api.common.jpa.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table

/**
 * A BrainBox-managed curriculum subject (N3). The client never carries a
 * hardcoded subject list: teacher settings request this catalogue from the
 * server, and platform staff add a subject by inserting a row (no client
 * release is needed). [gradeBands] is a JSON array of grade ranges such as
 * ["4-6"]; an empty list means the subject is offered in every grade.
 */
@Entity
@Table(name = "subjects")
class SubjectEntity : BaseEntity() {

    @Column(nullable = false, length = 32)
    var code: String = ""

    @Column(nullable = false, length = 160)
    var name: String = ""

    /** JSON array string of grade bands, e.g. ["1-3"]. */
    @Column(name = "grade_bands", columnDefinition = "text")
    var gradeBands: String? = null

    @Column(name = "is_active", nullable = false)
    var isActive: Boolean = true

    @Column(name = "sort_order", nullable = false)
    var sortOrder: Int = 0
}
