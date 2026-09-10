package com.afrithecus.brainbox.api.interview.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.util.UUID

/** Fixed question list captured when a session starts. */
@Entity
@Table(name = "interview_session_questions")
class InterviewSessionQuestionEntity {

    @Id
    @Column(nullable = false, updatable = false)
    var id: UUID = UUID.randomUUID()

    @Column(name = "session_id", nullable = false)
    var sessionId: UUID = UUID.randomUUID()

    @Column(name = "question_id", nullable = false)
    var questionId: UUID = UUID.randomUUID()

    @Column(name = "order_index", nullable = false)
    var orderIndex: Int = 0
}
