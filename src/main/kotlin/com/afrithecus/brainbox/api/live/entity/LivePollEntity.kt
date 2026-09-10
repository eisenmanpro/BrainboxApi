package com.afrithecus.brainbox.api.live.entity

import com.afrithecus.brainbox.api.common.jpa.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.util.UUID

/** A poll run inside a live class (doc 05 §4.6). */
@Entity
@Table(name = "live_polls")
class LivePollEntity : BaseEntity() {

    @Column(name = "class_id", nullable = false)
    var classId: UUID = UUID.randomUUID()

    @Column(name = "created_by", nullable = false)
    var createdBy: UUID = UUID.randomUUID()

    @Column(nullable = false, length = 500)
    var question: String = ""

    /** JSON array string of options. */
    @Column(nullable = false, columnDefinition = "text")
    var options: String = "[]"

    @Column(name = "is_active", nullable = false)
    var isActive: Boolean = true
}
