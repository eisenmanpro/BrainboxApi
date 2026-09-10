package com.afrithecus.brainbox.api.mastery

import com.afrithecus.brainbox.api.common.error.invalidArgument
import com.afrithecus.brainbox.api.exams.repository.ExamQuestionRepository
import com.afrithecus.brainbox.api.exams.repository.ExamRepository
import com.afrithecus.brainbox.api.mastery.entity.TopicMasteryEntity
import com.afrithecus.brainbox.api.mastery.model.MasteryLevel
import com.afrithecus.brainbox.api.mastery.repository.TopicMasteryRepository
import com.afrithecus.brainbox.api.mastery.web.MasteryOverviewPayload
import com.afrithecus.brainbox.api.mastery.web.MasteryUpdateRequest
import com.afrithecus.brainbox.api.mastery.web.SubjectMasterySummaryPayload
import com.afrithecus.brainbox.api.mastery.web.TopicMasteryPayload
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID

/**
 * Topic mastery (doc 03 §6). The server accumulates attempts per (user, topic),
 * recomputes the score from cumulative accuracy and derives the band; the subject
 * is resolved from the matching exam-question topic where possible.
 */
@Service
class MasteryService(
    private val repository: TopicMasteryRepository,
    private val questionRepository: ExamQuestionRepository,
    private val examRepository: ExamRepository,
    private val clock: Clock,
) {

    @Transactional(readOnly = true)
    fun overview(userId: UUID): MasteryOverviewPayload {
        val rows = repository.findAllByUserIdOrderByScoreAsc(userId)
        val subjects = rows.groupBy { it.subject }.map { (subject, list) -> subjectSummary(subject, list) }
            .sortedBy { it.subject }
        return MasteryOverviewPayload(
            overallMastery = average(rows.map { it.score }),
            totalTopics = rows.size,
            masteredCount = rows.count { MasteryLevel.fromScore(it.score) == MasteryLevel.MASTER },
            proficientCount = rows.count { MasteryLevel.fromScore(it.score) == MasteryLevel.PROFICIENT },
            developingCount = rows.count { MasteryLevel.fromScore(it.score) == MasteryLevel.DEVELOPING },
            noviceCount = rows.count { MasteryLevel.fromScore(it.score) == MasteryLevel.NOVICE },
            subjectsSummary = subjects,
            recentImprovements = rows.filter { it.score > it.previousScore }.sortedByDescending { it.score - it.previousScore }.take(5).map(::payload),
            strugglingTopics = rows.filter { it.score < 50.0 }.sortedBy { it.score }.take(5).map(::payload),
        )
    }

    @Transactional(readOnly = true)
    fun subjectMastery(userId: UUID, subjectRaw: String): SubjectMasterySummaryPayload {
        val subject = subjectRaw.trim()
        val rows = repository.findAllByUserIdAndSubjectOrderByScoreAsc(userId, subject)
        if (rows.isEmpty()) {
            return SubjectMasterySummaryPayload(
                subject = subject,
                overallScore = 0f,
                topicsMastered = 0,
                totalTopics = 0,
                recommendedFocus = "Review core concepts",
            )
        }
        return subjectSummary(subject, rows)
    }

    @Transactional(readOnly = true)
    fun weakTopics(userId: UUID): List<TopicMasteryPayload> =
        repository.findAllByUserIdOrderByScoreAsc(userId).filter { it.score < 50.0 }.map(::payload)

    @Transactional
    fun update(userId: UUID, request: MasteryUpdateRequest): TopicMasteryPayload {
        if (request.correctAnswers > request.totalQuestions) {
            throw invalidArgument("correctAnswers cannot exceed totalQuestions")
        }
        val topicId = request.topicId.trim()
        val question = questionRepository.findFirstByTopicIgnoreCase(topicId)
        val subject = question?.let { examRepository.findById(it.examId).map { exam -> exam.subject }.orElse(null) } ?: "General"
        val topicName = question?.topic?.takeIf { it.isNotBlank() } ?: topicId

        val row = repository.findByUserIdAndTopicId(userId, topicId) ?: TopicMasteryEntity().apply {
            this.userId = userId
            this.topicId = topicId
        }
        val previous = row.score
        row.topicName = topicName
        row.subject = subject
        row.previousScore = previous
        row.attemptsCount += 1
        row.questionsAttempted += request.totalQuestions
        row.correctAnswers += request.correctAnswers
        row.totalTimeSeconds += request.timeSpentSeconds.toLong()
        row.score = if (row.questionsAttempted > 0) round2(row.correctAnswers * 100.0 / row.questionsAttempted) else 0.0
        row.lastPracticed = clock.instant()
        val saved = repository.save(row)
        val next = repository.findAllByUserIdAndSubjectOrderByScoreAsc(userId, subject)
            .firstOrNull { it.topicId != saved.topicId && it.score < saved.score }
            ?.topicName
        return payload(saved, next)
    }

    /** Overall mastery percentage, used by achievements/leaderboards. */
    @Transactional(readOnly = true)
    fun overallScore(userId: UUID): Double =
        average(repository.findAllByUserIdOrderByScoreAsc(userId).map { it.score }).toDouble()

    // ------------------------------------------------------------ internals

    private fun subjectSummary(subject: String, rows: List<TopicMasteryEntity>): SubjectMasterySummaryPayload {
        val weakest = rows.first()
        val strongest = rows.last()
        return SubjectMasterySummaryPayload(
            subject = subject,
            overallScore = average(rows.map { it.score }),
            topicsMastered = rows.count { MasteryLevel.fromScore(it.score) == MasteryLevel.MASTER },
            totalTopics = rows.size,
            weakestTopic = payload(weakest),
            strongestTopic = payload(strongest),
            recommendedFocus = weakest.topicName,
        )
    }

    private fun payload(entity: TopicMasteryEntity, recommendedNextTopic: String? = null): TopicMasteryPayload =
        TopicMasteryPayload(
            topicId = entity.topicId,
            topicName = entity.topicName,
            subject = entity.subject,
            score = entity.score.toFloat(),
            level = MasteryLevel.fromScore(entity.score).name,
            attemptsCount = entity.attemptsCount,
            lastPracticed = entity.lastPracticed.toEpochMilli(),
            questionsAttempted = entity.questionsAttempted,
            correctAnswers = entity.correctAnswers,
            averageTimePerQuestion = if (entity.questionsAttempted > 0) {
                (entity.totalTimeSeconds.toDouble() / entity.questionsAttempted).toFloat()
            } else {
                0f
            },
            trend = (entity.score - entity.previousScore).toFloat(),
            recommendedNextTopic = recommendedNextTopic,
        )

    private fun average(values: List<Double>): Float =
        if (values.isEmpty()) 0f else round2(values.average()).toFloat()

    private fun round2(value: Double): Double = Math.round(value * 100.0) / 100.0
}
