package com.afrithecus.brainbox.api.homework.entity

import com.afrithecus.brainbox.api.exams.model.QuestionType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.util.UUID

/** A question of an EXAM_QUESTION_SET homework. Keys stay server-side. */
@Entity
@Table(name = "homework_questions")
class HomeworkQuestionEntity {

    @Id
    @Column(nullable = false, updatable = false)
    var id: UUID = UUID.randomUUID()

    @Column(name = "homework_id", nullable = false, length = 128)
    var homeworkId: String = ""

    @Column(nullable = false)
    var text: String = ""

    @Enumerated(EnumType.STRING)
    @Column(name = "q_type", nullable = false, length = 32)
    var qType: QuestionType = QuestionType.MCQ

    @Column(columnDefinition = "text")
    var options: String? = null

    @Column(name = "correct_answer", columnDefinition = "text")
    var correctAnswer: String? = null

    @Column(columnDefinition = "text")
    var explanation: String? = null

    @Column(nullable = false)
    var points: Int = 1

    @Column(name = "order_index", nullable = false)
    var orderIndex: Int = 0
}
