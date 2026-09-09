package com.afrithecus.brainbox.api.doubt.entity

import com.afrithecus.brainbox.api.common.jpa.BaseEntity
import com.afrithecus.brainbox.api.doubt.model.QuestionStatus
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import java.util.UUID

/** A forum doubt question (doc 05 §3.1). */
@Entity
@Table(name = "doubt_questions")
class DoubtQuestionEntity : BaseEntity() {

    @Column(nullable = false)
    var title: String = ""

    @Column(nullable = false)
    var body: String = ""

    @Column(nullable = false, length = 128)
    var subject: String = ""

    /** JSON array of tags. */
    @Column(columnDefinition = "text")
    var tags: String? = null

    @Column(name = "author_id", nullable = false)
    var authorId: UUID = UUID.randomUUID()

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    var status: QuestionStatus = QuestionStatus.OPEN

    @Column(name = "vote_count", nullable = false)
    var voteCount: Int = 0

    @Column(name = "view_count", nullable = false)
    var viewCount: Int = 0
}
