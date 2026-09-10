package com.afrithecus.brainbox.api.interview.entity

import com.afrithecus.brainbox.api.interview.model.InterviewRubricType
import com.afrithecus.brainbox.api.interview.model.PracticeType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/** Interview question bank entry (doc 06 §2.3). */
@Entity
@Table(name = "interview_questions")
class InterviewQuestionEntity {

    @Id
    @Column(nullable = false, updatable = false)
    var id: UUID = UUID.randomUUID()

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 48)
    var type: PracticeType = PracticeType.UNIVERSITY_INTERVIEW

    @Column(nullable = false, columnDefinition = "text")
    var text: String = ""

    @Column(nullable = false, length = 64)
    var category: String = "General"

    @Column(nullable = false)
    var difficulty: Int = 1

    @Column(name = "order_index", nullable = false)
    var orderIndex: Int = 0

    @Column(name = "sample_answer", columnDefinition = "text")
    var sampleAnswer: String? = null

    /** JSON array string of expected keywords. */
    @Column(columnDefinition = "text")
    var keywords: String? = null

    @Column(name = "min_words", nullable = false)
    var minWords: Int = 20

    @Column(name = "max_words", nullable = false)
    var maxWords: Int = 200

    /** JSON array string of structure phrases. */
    @Column(name = "structure_phrases", columnDefinition = "text")
    var structurePhrases: String? = null

    @Enumerated(EnumType.STRING)
    @Column(name = "rubric_type", nullable = false, length = 16)
    var rubricType: InterviewRubricType = InterviewRubricType.NONE

    @Column(name = "company_focus", length = 160)
    var companyFocus: String? = null

    @Column(name = "school_focus", length = 160)
    var schoolFocus: String? = null

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant = Instant.now()
}
