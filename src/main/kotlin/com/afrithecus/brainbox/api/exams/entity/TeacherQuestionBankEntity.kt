package com.afrithecus.brainbox.api.exams.entity

import com.afrithecus.brainbox.api.common.jpa.BaseEntity
import com.afrithecus.brainbox.api.exams.model.QuestionType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import java.util.UUID

/** A reusable question a teacher can drop into any exam. */
@Entity
@Table(name = "teacher_question_bank")
class TeacherQuestionBankEntity : BaseEntity() {

    /** Client-supplied id; repeated adds for the same id upsert. */
    @Column(name = "client_id", nullable = false, length = 80)
    var clientId: String = ""

    @Column(name = "teacher_id", nullable = false)
    var teacherId: UUID = UUID.randomUUID()

    @Column(name = "school_id")
    var schoolId: UUID? = null

    @Column(nullable = false, columnDefinition = "text")
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

    @Column(name = "cbc_strand_tag", length = 128)
    var cbcStrandTag: String? = null

    @Column(length = 128)
    var subject: String? = null

    @Column(name = "grade_level")
    var gradeLevel: Int? = null
}
