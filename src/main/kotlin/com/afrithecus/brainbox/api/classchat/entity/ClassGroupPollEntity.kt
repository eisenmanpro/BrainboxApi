package com.afrithecus.brainbox.api.classchat.entity

import com.afrithecus.brainbox.api.common.jpa.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.util.UUID

/** A poll posted inside a class chat group (doc 04 §12.4). */
@Entity
@Table(name = "class_group_polls")
class ClassGroupPollEntity : BaseEntity() {

    @Column(name = "group_id", nullable = false)
    var groupId: UUID = UUID.randomUUID()

    @Column(name = "created_by", nullable = false)
    var createdBy: UUID = UUID.randomUUID()

    @Column(nullable = false, length = 500)
    var question: String = ""

    /** JSON array string of option labels. */
    @Column(nullable = false, columnDefinition = "text")
    var options: String = "[]"

    @Column(name = "is_active", nullable = false)
    var isActive: Boolean = true
}
