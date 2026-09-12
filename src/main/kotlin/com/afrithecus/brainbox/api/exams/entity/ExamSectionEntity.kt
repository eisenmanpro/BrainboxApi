package com.afrithecus.brainbox.api.exams.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.util.UUID

/** A named question section inside an authored exam. */
@Entity
@Table(name = "exam_sections")
class ExamSectionEntity {

    @Id
    @Column(nullable = false, updatable = false)
    var id: UUID = UUID.randomUUID()

    @Column(name = "exam_id", nullable = false)
    var examId: UUID = UUID.randomUUID()

    /** Client-supplied section id; stable across exam re-saves. */
    @Column(name = "client_id", length = 80)
    var clientId: String = ""

    @Column(nullable = false)
    var title: String = ""

    @Column(columnDefinition = "text")
    var instructions: String? = null

    @Column(name = "duration_minutes")
    var durationMinutes: Int? = null

    @Column(name = "sort_order", nullable = false)
    var sortOrder: Int = 0
}
