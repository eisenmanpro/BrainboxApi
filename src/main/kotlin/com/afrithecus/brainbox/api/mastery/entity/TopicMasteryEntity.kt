package com.afrithecus.brainbox.api.mastery.entity

import com.afrithecus.brainbox.api.common.jpa.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/** Cumulative mastery for one (user, topic) pair (doc 03 §6). */
@Entity
@Table(name = "topic_mastery")
class TopicMasteryEntity : BaseEntity() {

    @Column(name = "user_id", nullable = false)
    var userId: UUID = UUID.randomUUID()

    @Column(name = "topic_id", nullable = false, length = 160)
    var topicId: String = ""

    @Column(name = "topic_name", nullable = false, length = 200)
    var topicName: String = ""

    @Column(nullable = false, length = 128)
    var subject: String = "General"

    /** 0-100 cumulative accuracy. */
    @Column(nullable = false)
    var score: Double = 0.0

    /** Score before the most recent update, used for the trend delta. */
    @Column(name = "previous_score", nullable = false)
    var previousScore: Double = 0.0

    @Column(name = "attempts_count", nullable = false)
    var attemptsCount: Int = 0

    @Column(name = "questions_attempted", nullable = false)
    var questionsAttempted: Int = 0

    @Column(name = "correct_answers", nullable = false)
    var correctAnswers: Int = 0

    @Column(name = "total_time_seconds", nullable = false)
    var totalTimeSeconds: Long = 0

    @Column(name = "last_practiced", nullable = false)
    var lastPracticed: Instant = Instant.now()
}
