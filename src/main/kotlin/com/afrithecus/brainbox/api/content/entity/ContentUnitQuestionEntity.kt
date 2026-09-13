package com.afrithecus.brainbox.api.content.entity

import com.afrithecus.brainbox.api.common.jpa.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.util.UUID

/** One ordered question attached to a content unit (and optionally a step). */
@Entity
@Table(name = "content_unit_questions")
class ContentUnitQuestionEntity : BaseEntity() {

    @Column(name = "unit_id", nullable = false)
    var unitId: UUID = UUID.randomUUID()

    @Column(name = "step_id")
    var stepId: UUID? = null

    @Column(name = "order_index", nullable = false)
    var orderIndex: Int = 0

    @Column(name = "q_type", nullable = false, length = 24)
    var qType: String = ""

    @Column(nullable = false, columnDefinition = "text")
    var text: String = ""

    @Column(columnDefinition = "text")
    var options: String? = null

    @Column(name = "correct_answer", columnDefinition = "text")
    var correctAnswer: String? = null

    @Column(columnDefinition = "text")
    var explanation: String? = null

    @Column(nullable = false)
    var points: Int = 1

    @Column(nullable = false)
    var difficulty: Int = 3

    @Column(name = "matching_pairs", columnDefinition = "text")
    var matchingPairs: String? = null
}
