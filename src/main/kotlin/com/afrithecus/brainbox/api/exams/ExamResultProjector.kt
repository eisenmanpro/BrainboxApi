package com.afrithecus.brainbox.api.exams

import com.afrithecus.brainbox.api.exams.entity.ExamEntity
import com.afrithecus.brainbox.api.exams.entity.ExamQuestionEntity
import com.afrithecus.brainbox.api.exams.entity.ExamSubmissionEntity
import com.afrithecus.brainbox.api.exams.web.ExamResultPayload
import com.afrithecus.brainbox.api.exams.web.ExamSubmissionDetailsPayload
import com.afrithecus.brainbox.api.exams.web.ExamWeakAreaPayload
import com.afrithecus.brainbox.api.exams.web.QuestionGradingDetailPayload
import com.afrithecus.brainbox.api.exams.web.QuestionPayload
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import kotlin.math.roundToInt

/**
 * Builds the student-facing exam result and submission payloads (doc 02 §3.3/§3.4).
 * Answer keys and explanations never cross this boundary: grading details expose
 * correctness and points only. Topic mastery is reported as a 0..1 fraction, the
 * scale the client's topic breakdown renders.
 */
@Component
class ExamResultProjector(
    private val mapper: ObjectMapper,
    private val autoGrader: AutoGrader,
) {

    /**
     * Full result for a graded submission. [submission] may come from server
     * grading (online exams) or a self-graded past-paper attempt; the latter has
     * no stored per-question detail, so its client-reported score is treated as
     * fully auto-graded.
     */
    fun result(
        exam: ExamEntity,
        submission: ExamSubmissionEntity,
        questions: List<ExamQuestionEntity>,
        percentile: Int,
    ): ExamResultPayload {
        val projection = project(submission.questionResults, questions)
        return ExamResultPayload(
            id = submission.id.toString(),
            title = exam.title,
            score = submission.score,
            percentage = submission.percentage.toDouble(),
            totalPoints = submission.totalPoints,
            percentile = percentile,
            topicBreakdown = projection.topicBreakdown,
            timePerQuestion = emptyMap(),
            status = STATUS_PUBLISHED,
            markingType = projection.markingType,
            gradingDetails = projection.gradingDetails,
            weakAreas = projection.weakAreas,
            autoGradedScore = if (projection.graded) projection.autoGradedScore else submission.score,
            pendingReviewScore = projection.pendingReviewScore,
        )
    }

    /** Post-exam review payload; questions are stripped of keys before they get here. */
    fun submissionDetails(
        exam: ExamEntity,
        submission: ExamSubmissionEntity,
        questions: List<ExamQuestionEntity>,
        questionPayloads: List<QuestionPayload>,
    ): ExamSubmissionDetailsPayload = ExamSubmissionDetailsPayload(
        examId = exam.id.toString(),
        title = exam.title,
        questions = questionPayloads,
        userAnswers = answersAsMap(submission.answers),
        submittedAt = submission.submittedAt.toEpochMilli(),
        status = STATUS_PUBLISHED,
        markingType = markingType(questions),
    )

    // ------------------------------------------------------------ internals

    private class Projection(
        val topicBreakdown: Map<String, Double>,
        val markingType: String,
        val gradingDetails: List<QuestionGradingDetailPayload>,
        val weakAreas: List<ExamWeakAreaPayload>,
        val autoGradedScore: Int,
        val pendingReviewScore: Int,
        val graded: Boolean,
    )

    private data class Entry(
        val questionId: String,
        val isCorrect: Boolean,
        val pointsEarned: Int,
        val reviewed: Boolean,
    )

    private fun project(json: String?, questions: List<ExamQuestionEntity>): Projection {
        val meta = questions.associateBy { it.id.toString() }
        val entries = parse(json)
        val earned = LinkedHashMap<String, Int>()
        val possible = LinkedHashMap<String, Int>()
        val details = mutableListOf<QuestionGradingDetailPayload>()
        var autoGraded = 0
        var pendingReview = 0
        var hasEssay = false

        for (entry in entries) {
            val info = meta[entry.questionId] ?: continue
            val topic = info.topic?.takeIf { it.isNotBlank() } ?: "General"
            val essay = !autoGrader.isAutoGradable(info.qType)
            if (essay) {
                // The exam still requires teacher marking, but a reviewed essay
                // contributes its awarded mark instead of the whole question
                // staying in the pending-review pot.
                hasEssay = true
                if (entry.reviewed) autoGraded += entry.pointsEarned else pendingReview += info.points
            } else {
                autoGraded += entry.pointsEarned
            }
            earned[topic] = (earned[topic] ?: 0) + entry.pointsEarned
            possible[topic] = (possible[topic] ?: 0) + info.points
            details += QuestionGradingDetailPayload(
                questionId = entry.questionId,
                questionText = info.text,
                isCorrect = entry.isCorrect,
                scoreAwarded = entry.pointsEarned,
                pointsPossible = info.points,
                requiresExplanation = essay,
                isKeyQuestion = info.difficulty >= KEY_QUESTION_DIFFICULTY,
                cbcStrand = info.topic,
            )
        }

        val breakdown = possible.mapValues { (topic, total) -> fraction(earned[topic] ?: 0, total) }
        val weakAreas = breakdown.entries
            .filter { it.value < WEAK_THRESHOLD }
            .sortedBy { it.value }
            .map { (topic, value) ->
                ExamWeakAreaPayload(
                    cbcStrand = topic,
                    masteryLevel = value * 100.0,
                    severity = if (value < CRITICAL_THRESHOLD) "HIGH" else "MEDIUM",
                    affectedStudents = 0,
                    recommendedAction = "Revise " + topic,
                )
            }
        return Projection(
            topicBreakdown = breakdown,
            markingType = if (hasEssay) MARKING_TEACHER_REVIEW else MARKING_AUTOMATIC,
            gradingDetails = details,
            weakAreas = weakAreas,
            autoGradedScore = autoGraded,
            pendingReviewScore = pendingReview,
            graded = entries.isNotEmpty(),
        )
    }

    private fun markingType(questions: List<ExamQuestionEntity>): String =
        if (questions.any { !autoGrader.isAutoGradable(it.qType) }) MARKING_TEACHER_REVIEW else MARKING_AUTOMATIC

    /**
     * Answers as the client's Map<String, String> contract: multi-select arrays
     * and matching maps are stringified the same way the grader reads them.
     */
    private fun answersAsMap(json: String?): Map<String, String>? {
        if (json.isNullOrBlank()) return null
        val node = runCatching { mapper.readTree(json) }.getOrNull() ?: return null
        if (!node.isObject) return null
        return node.properties().associate { it.key to answerText(it.value) }
    }

    private fun answerText(node: JsonNode): String = when {
        node.isValueNode -> node.asString()
        node.isArray -> (0 until node.size()).joinToString(", ") { node.get(it).asString() }
        node.isObject -> node.properties().joinToString(", ") { it.key + ": " + it.value.asString() }
        else -> node.toString()
    }

    /** Earned/possible as a 0..1 fraction rounded to two decimals. */
    private fun fraction(earned: Int, possible: Int): Double =
        if (possible <= 0) 0.0 else (earned * 100.0 / possible).roundToInt() / 100.0

    private fun parse(json: String?): List<Entry> {
        if (json.isNullOrBlank()) return emptyList()
        val node = runCatching { mapper.readTree(json) }.getOrNull() ?: return emptyList()
        if (!node.isArray) return emptyList()
        return (0 until node.size()).mapNotNull { i ->
            val item = node.get(i)
            val questionId = item.get("questionId")?.asString() ?: return@mapNotNull null
            Entry(
                questionId = questionId,
                isCorrect = item.get("isCorrect")?.asBoolean() ?: false,
                pointsEarned = item.get("pointsEarned")?.intValue() ?: 0,
                reviewed = item.get("reviewed")?.asBoolean() ?: false,
            )
        }
    }

    private companion object {
        const val STATUS_PUBLISHED = "PUBLISHED"
        const val MARKING_AUTOMATIC = "AUTOMATIC"
        const val MARKING_TEACHER_REVIEW = "TEACHER_REVIEW"
        const val KEY_QUESTION_DIFFICULTY = 4
        const val WEAK_THRESHOLD = 0.5
        const val CRITICAL_THRESHOLD = 0.3
    }
}
