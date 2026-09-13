package com.afrithecus.brainbox.api.feedback.entity

import com.afrithecus.brainbox.api.common.jpa.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.util.UUID

/** A reusable feedback template (doc 04 section 7.1). */
@Entity
@Table(name = "teacher_feedback_templates")
class FeedbackTemplateEntity : BaseEntity() {

    @Column(name = "client_id", nullable = false, length = 80)
    var clientId: String = ""

    @Column(name = "teacher_id", nullable = false)
    var teacherId: UUID = UUID.randomUUID()

    @Column(nullable = false, length = 200)
    var title: String = ""

    @Column(nullable = false, columnDefinition = "text")
    var content: String = ""

    @Column(nullable = false, length = 64)
    var category: String = ""
}
