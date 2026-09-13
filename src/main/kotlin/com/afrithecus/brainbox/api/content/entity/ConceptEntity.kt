package com.afrithecus.brainbox.api.content.entity

import com.afrithecus.brainbox.api.common.jpa.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.util.UUID

/** A teachable concept in the content catalogue (Phase 7.1). */
@Entity
@Table(name = "concepts")
class ConceptEntity : BaseEntity() {

    @Column(nullable = false, length = 64)
    var code: String = ""

    @Column(nullable = false, length = 200)
    var name: String = ""

    @Column(columnDefinition = "text")
    var description: String? = null

    @Column(nullable = false, length = 64)
    var subject: String = ""

    @Column(name = "parent_id")
    var parentId: UUID? = null

    @Column(name = "sort_order", nullable = false)
    var sortOrder: Int = 0
}
