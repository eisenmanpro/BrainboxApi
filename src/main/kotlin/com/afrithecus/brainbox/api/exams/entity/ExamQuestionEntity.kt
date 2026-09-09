package com.afrithecus.brainbox.api.exams.entity

import com.afrithecus.brainbox.api.exams.model.QuestionType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.util.UUID

/**
 * A question belonging to an exam. Key material (correctAnswer, explanation,
 * matchingPairs) must never be serialized into student-facing payloads.
 */
@Entity
@Table(name = "exam_questions")
class ExamQuestionEntity {

    @Id
    @Column(nullable = false, updatable = false)
    var id: UUID = UUID.randomUUID()

    @Column(name = "exam_id", nullable = false)
    var examId: UUID = UUID.randomUUID()

    @Column(nullable = false)
    var text: String = ""

    @Enumerated(EnumType.STRING)
    @Column(name = "q_type", nullable = false, length = 32)
    var qType: QuestionType = QuestionType.MCQ

    /** JSON array string, e.g. ["A","B","C"] */
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

    /** JSON object string, e.g. {"a":"1"} */
    @Column(name = "matching_pairs", columnDefinition = "text")
    var matchingPairs: String? = null

    @Column(length = 255)
    var topic: String? = null

    @Column(length = 255)
    var subtopic: String? = null

    @Column(name = "order_index", nullable = false)
    var orderIndex: Int = 0
}
