package com.afrithecus.brainbox.api.interview.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/** Server-scored answer. Emotion metadata, when the client supplies it, is stored for analytics. */
@Entity
@Table(name = "interview_answers")
class InterviewAnswerEntity {

    @Id
    @Column(nullable = false, updatable = false)
    var id: UUID = UUID.randomUUID()

    @Column(name = "session_id", nullable = false)
    var sessionId: UUID = UUID.randomUUID()

    @Column(name = "question_id", nullable = false)
    var questionId: UUID = UUID.randomUUID()

    @Column(name = "transcribed_text", nullable = false, columnDefinition = "text")
    var transcribedText: String = ""

    @Column(name = "word_count", nullable = false)
    var wordCount: Int = 0

    @Column(name = "clarity_score", nullable = false)
    var clarityScore: Int = 0

    @Column(name = "filler_word_count", nullable = false)
    var fillerWordCount: Int = 0

    /** JSON object string of filler -> replacement. */
    @Column(name = "filler_replacements", columnDefinition = "text")
    var fillerReplacements: String? = null

    @Column(nullable = false)
    var pace: Int = 0

    @Column(name = "keyword_match_count", nullable = false)
    var keywordMatchCount: Int = 0

    @Column(name = "total_keywords", nullable = false)
    var totalKeywords: Int = 0

    @Column(name = "structure_phrases_found", nullable = false)
    var structurePhrasesFound: Int = 0

    @Column(nullable = false, columnDefinition = "text")
    var feedback: String = ""

    @Column(nullable = false)
    var score: Int = 0

    /** JSON object string of the rubric score, null when the question has no rubric. */
    @Column(name = "rubric_json", columnDefinition = "text")
    var rubricJson: String? = null

    @Column(name = "diction_score", nullable = false)
    var dictionScore: Int = 0

    @Column(name = "pronunciation_score", nullable = false)
    var pronunciationScore: Int = 0

    @Column(length = 32)
    var emotion: String? = null

    @Column(name = "emotion_confidence")
    var emotionConfidence: Double? = null

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant = Instant.now()
}
