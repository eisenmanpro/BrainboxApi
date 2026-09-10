package com.afrithecus.brainbox.api.analytics

import com.afrithecus.brainbox.api.analytics.web.AnalyticsExamResultPayload
import com.afrithecus.brainbox.api.analytics.web.AnalyticsQuestionResultPayload
import com.afrithecus.brainbox.api.analytics.web.AnalyticsWeakAreaPayload
import com.afrithecus.brainbox.api.exams.entity.ExamQuestionEntity
import com.afrithecus.brainbox.api.exams.model.QuestionType
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.util.UUID
import kotlin.math.roundToInt

/** A graded exam attempt normalised for analytics. */
data class Attempt(
    val submissionId: UUID,
    val studentId: UUID,
    val examId: UUID,
    val title: String,
    val subject: String,
    val score: Int,
    val totalPoints: Int,
    val percentage: Double,
    val submittedAt: Instant,
    val questionResultsJson: String?,
)

private data class ResultEntry(val questionId: String, val isCorrect: Boolean, val pointsEarned: Int)

/** Per-topic earned/possible points for one attempt. */
data class TopicTally(val topic: String, val earned: Int, val possible: Int)

private class Tally {
    val topicEarned = LinkedHashMap<String, Int>()
    val topicPossible = LinkedHashMap<String, Int>()
    var autoGradedScore = 0
    var pendingReviewScore = 0
    var hasEssay = false
}

/**
 * Turns stored submissions into the exam-result payload the client renders.
 * Only correctness/points are exposed: correctAnswer and explanation stay
 * server-side (doc 02 Appendix A).
 */
@Component
class ExamResultAssembler(private val mapper: ObjectMapper) {

    fun topicTally(json: String?, questions: List<ExamQuestionEntity>): List<TopicTally> {
        val meta = questions.associateBy { it.id.toString() }
        val earned = LinkedHashMap<String, Int>()
        val possible = LinkedHashMap<String, Int>()
        for (entry in parse(json)) {
            val info = meta[entry.questionId]
            val topic = info?.topic?.takeIf { it.isNotBlank() } ?: "General"
            earned[topic] = (earned[topic] ?: 0) + entry.pointsEarned
            possible[topic] = (possible[topic] ?: 0) + (info?.points ?: 0)
        }
        return possible.map { (topic, total) -> TopicTally(topic, earned[topic] ?: 0, total) }
    }

    fun toResult(attempt: Attempt, questions: List<ExamQuestionEntity>, percentile: Int): AnalyticsExamResultPayload {
        val meta = questions.associateBy { it.id.toString() }
        val tally = Tally()
        val details = mutableListOf<AnalyticsQuestionResultPayload>()
        for (entry in parse(attempt.questionResultsJson)) {
            val info = meta[entry.questionId]
            val topic = info?.topic?.takeIf { it.isNotBlank() } ?: "General"
            val points = info?.points ?: 0
            val essay = info?.qType == QuestionType.ESSAY
            if (essay) {
                tally.hasEssay = true
                tally.pendingReviewScore += points
            } else {
                tally.autoGradedScore += entry.pointsEarned
            }
            tally.topicEarned[topic] = (tally.topicEarned[topic] ?: 0) + entry.pointsEarned
            tally.topicPossible[topic] = (tally.topicPossible[topic] ?: 0) + points
            details += AnalyticsQuestionResultPayload(
                questionId = entry.questionId,
                questionText = info?.text ?: "",
                isCorrect = entry.isCorrect,
                scoreAwarded = entry.pointsEarned,
                pointsPossible = points,
                requiresExplanation = essay,
                isKeyQuestion = (info?.difficulty ?: 0) >= 4,
                cbcStrand = info?.topic,
            )
        }
        return payload(
            id = attempt.submissionId.toString(),
            title = attempt.title,
            score = attempt.score,
            totalPoints = attempt.totalPoints,
            percentage = attempt.percentage,
            percentile = percentile,
            tally = tally,
            details = details,
            weakAreaCounts = tally.topicEarned.keys.associateWith { 1 },
        )
    }

    /** Class-level result: one row per exam, aggregated across the roster. */
    fun toClassResult(examId: UUID, title: String, attempts: List<Attempt>, questions: List<ExamQuestionEntity>, percentile: Int): AnalyticsExamResultPayload {
        val meta = questions.associateBy { it.id.toString() }
        val tally = Tally()
        val below = LinkedHashMap<String, MutableSet<UUID>>()
        for (attempt in attempts) {
            for (entry in parse(attempt.questionResultsJson)) {
                val info = meta[entry.questionId]
                val topic = info?.topic?.takeIf { it.isNotBlank() } ?: "General"
                val points = info?.points ?: 0
                val essay = info?.qType == QuestionType.ESSAY
                if (essay) {
                    tally.hasEssay = true
                    tally.pendingReviewScore += points
                } else {
                    tally.autoGradedScore += entry.pointsEarned
                }
                tally.topicEarned[topic] = (tally.topicEarned[topic] ?: 0) + entry.pointsEarned
                tally.topicPossible[topic] = (tally.topicPossible[topic] ?: 0) + points
            }
        }
        // Count how many students fall below 50 for each topic (per-attempt tally).
        for (attempt in attempts) {
            val earnedByTopic = LinkedHashMap<String, Int>()
            val possibleByTopic = LinkedHashMap<String, Int>()
            for (entry in parse(attempt.questionResultsJson)) {
                val info = meta[entry.questionId]
                val topic = info?.topic?.takeIf { it.isNotBlank() } ?: "General"
                earnedByTopic[topic] = (earnedByTopic[topic] ?: 0) + entry.pointsEarned
                possibleByTopic[topic] = (possibleByTopic[topic] ?: 0) + (info?.points ?: 0)
            }
            possibleByTopic.forEach { (topic, possible) ->
                if (percent(earnedByTopic[topic] ?: 0, possible) < 50.0) {
                    below.getOrPut(topic) { linkedSetOf() }.add(attempt.submissionId)
                }
            }
        }
        val topics = tally.topicPossible.keys
        return payload(
            id = examId.toString(),
            title = title,
            score = if (attempts.isEmpty()) 0 else (attempts.map { it.score }.average()).roundToInt(),
            totalPoints = attempts.maxOfOrNull { it.totalPoints } ?: 0,
            percentage = if (attempts.isEmpty()) 0.0 else CbcGrades.round2(attempts.map { it.percentage }.average()),
            percentile = percentile,
            tally = tally,
            details = emptyList(),
            weakAreaCounts = topics.associateWith { below[it]?.size ?: 0 },
        )
    }

    private fun payload(
        id: String,
        title: String,
        score: Int,
        totalPoints: Int,
        percentage: Double,
        percentile: Int,
        tally: Tally,
        details: List<AnalyticsQuestionResultPayload>,
        weakAreaCounts: Map<String, Int>,
    ): AnalyticsExamResultPayload {
        val breakdown = LinkedHashMap<String, Float>()
        tally.topicPossible.forEach { (topic, possible) ->
            breakdown[topic] = percent(tally.topicEarned[topic] ?: 0, possible).toFloat()
        }
        val weakAreas = breakdown.entries
            .filter { it.value < 50f }
            .sortedBy { it.value }
            .map { (topic, value) ->
                AnalyticsWeakAreaPayload(
                    cbcStrand = topic,
                    masteryLevel = value.toDouble(),
                    severity = if (value < 30f) "HIGH" else "MEDIUM",
                    affectedStudents = weakAreaCounts[topic] ?: 0,
                    recommendedAction = "Revise " + topic,
                )
            }
        return AnalyticsExamResultPayload(
            id = id,
            title = title,
            score = score,
            percentage = percentage,
            totalPoints = totalPoints,
            percentile = percentile,
            topicBreakdown = breakdown,
            timePerQuestion = emptyMap(),
            status = "PUBLISHED",
            markingType = if (tally.hasEssay) "TEACHER_REVIEW" else "AUTOMATIC",
            gradingDetails = details,
            weakAreas = weakAreas,
            autoGradedScore = tally.autoGradedScore,
            pendingReviewScore = tally.pendingReviewScore,
        )
    }

    private fun percent(earned: Int, possible: Int): Double =
        if (possible <= 0) 0.0 else CbcGrades.round2(earned * 100.0 / possible)

    private fun parse(json: String?): List<ResultEntry> {
        if (json.isNullOrBlank()) return emptyList()
        val node = runCatching { mapper.readTree(json) }.getOrNull() ?: return emptyList()
        if (!node.isArray) return emptyList()
        return (0 until node.size()).mapNotNull { i ->
            val item = node.get(i)
            val questionId = item.get("questionId")?.asString() ?: return@mapNotNull null
            ResultEntry(
                questionId = questionId,
                isCorrect = item.get("isCorrect")?.asBoolean() ?: false,
                pointsEarned = item.get("pointsEarned")?.intValue() ?: 0,
            )
        }
    }
}
