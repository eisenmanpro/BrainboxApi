package com.afrithecus.brainbox.api.cbcratings.entity

import com.afrithecus.brainbox.api.common.jpa.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table

/** A CBC curriculum strand (doc 04 CBC analytics). */
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
}
