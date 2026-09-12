package com.afrithecus.brainbox.api.exams

import com.afrithecus.brainbox.api.classes.repository.TeacherClassRepository
import com.afrithecus.brainbox.api.common.error.ApiErrorCode
import com.afrithecus.brainbox.api.common.error.ApiException
import com.afrithecus.brainbox.api.common.error.invalidArgument
import com.afrithecus.brainbox.api.common.error.notFound
import com.afrithecus.brainbox.api.exams.entity.ExamEntity
import com.afrithecus.brainbox.api.exams.entity.ExamQuestionEntity
import com.afrithecus.brainbox.api.exams.entity.ExamRemediationEntity
import com.afrithecus.brainbox.api.exams.entity.ExamReviewMarkEntity
import com.afrithecus.brainbox.api.exams.entity.ExamSubmissionEntity
import com.afrithecus.brainbox.api.exams.model.QuestionType
import com.afrithecus.brainbox.api.exams.repository.ExamQuestionRepository
import com.afrithecus.brainbox.api.exams.repository.ExamRemediationRepository
import com.afrithecus.brainbox.api.exams.repository.ExamRepository
import com.afrithecus.brainbox.api.exams.repository.ExamReviewMarkRepository
import com.afrithecus.brainbox.api.exams.repository.ExamSubmissionRepository
import com.afrithecus.brainbox.api.exams.web.ExamAnalysisReportPayload
import com.afrithecus.brainbox.api.exams.web.KeyQuestionsSummaryPayload
import com.afrithecus.brainbox.api.exams.web.QuestionGradingDetailPayload
import com.afrithecus.brainbox.api.exams.web.QuestionStatsPayload
import com.afrithecus.brainbox.api.exams.web.RemediationAssignmentPayload
import com.afrithecus.brainbox.api.exams.web.RemediationSavePayload
import com.afrithecus.brainbox.api.exams.web.StudentExamPerformancePayload
import com.afrithecus.brainbox.api.exams.web.TeacherReviewQueueItemPayload
import com.afrithecus.brainbox.api.exams.web.WeakAreaPayload
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.util.UUID
import kotlin.math.round
import kotlin.math.sqrt

/**
 * Teacher-side exam intelligence (docs/ongoing/api_exams_changes.md): the
 * analysis report that feeds key questions and remediation, the essay review
 * queue, delta-safe review marks and the replace-all remediation assignments.
 * Units match the client exactly: scores are percentages 0-100, passRate and
 * mastery are fractions 0-1.
 */
@Service
class TeacherExamAnalysisService(
    private val examService: TeacherExamService,
    private val examRepository: ExamRepository,
    private val questionRepository: ExamQuestionRepository,
    private val submissionRepository: ExamSubmissionRepository,
    private val reviewMarkRepository: ExamReviewMarkRepository,
    private val remediationRepository: ExamRemediationRepository,
    private val classRepository: TeacherClassRepository,
    private val userRepository: UserRepository,
    private val autoGrader: AutoGrader,
    private val mapper: ObjectMapper,
    private val clock: Clock,
) {

    @Transactional(readOnly = true)
    fun analysis(teacher: UserEntity, examId: String): ExamAnalysisReportPayload {
        examService.requireTeacher(teacher)
        val exam = examService.requireOwnedExam(teacher, examId)
        val questions = examService.questionsOf(exam.id)
        val submissions = submissionRepository.findAllByExamId(exam.id)
        val reviews = reviewsFor(submissions)
        val students = userRepository.findAllById(submissions.map { it.userId }).associateBy { it.id }
        val className = className(exam)

        val percentages = submissions.map { it.percentage }.sorted()
        val total = submissions.size
        val average = if (total == 0) 0.0 else percentages.average()
        val stddev =
            if (total == 0) 0.0 else sqrt(percentages.map { (it - average) * (it - average) }.average())
        val passRate = if (total == 0) 0.0 else percentages.count { it >= 50 }.toDouble() / total

        val performances = submissions.sortedByDescending { it.percentage }.mapIndexed { index, submission ->
            val breakdown = breakdown(submission, questions, reviews)
            StudentExamPerformancePayload(
                studentId = submission.userId.toString(),
                studentName = students[submission.userId]?.name ?: "Student",
                score = submission.score,
                maxScore = submission.totalPoints,
                percentage = submission.percentage.toDouble(),
                rank = index + 1,
                weakAreas = weakAreas(listOf(breakdown)),
                questionBreakdown = breakdown,
            )
        }

        return ExamAnalysisReportPayload(
            examId = exam.clientId ?: exam.id.toString(),
            className = className,
            totalStudents = total,
            averageScore = round2(average),
            medianScore = round2(median(percentages)),
            standardDeviation = round2(stddev),
            highestScore = percentages.lastOrNull() ?: 0,
            lowestScore = percentages.firstOrNull() ?: 0,
            passRate = round2(passRate),
            keyQuestionsSummary = keyQuestionsSummary(exam, questions, submissions),
            weakAreas = weakAreas(performances.map { it.questionBreakdown }),
            studentPerformances = performances,
        )
    }

    /** All reviewable pairs across the teacher's exams that nobody has marked yet. */
    @Transactional(readOnly = true)
    fun reviewQueue(teacher: UserEntity): List<TeacherReviewQueueItemPayload> {
        examService.requireTeacher(teacher)
        val exams = examRepository.findAllByCreatedByOrderByCreatedAtDesc(teacher.id)
        if (exams.isEmpty()) return emptyList()
        val examById = exams.associateBy { it.id }
        val submissions = submissionRepository.findAllByExamIdIn(examById.keys)
        if (submissions.isEmpty()) return emptyList()
        val reviewed = reviewMarkRepository.findAllBySubmissionIdIn(submissions.map { it.id })
            .map { it.submissionId to it.questionId }
            .toSet()
        val students = userRepository.findAllById(submissions.map { it.userId }).associateBy { it.id }

        val items = mutableListOf<TeacherReviewQueueItemPayload>()
        for (submission in submissions) {
            val exam = examById[submission.examId] ?: continue
            val questions = examService.questionsOf(submission.examId)
            val answers = answers(submission)
            for (question in questions) {
                if (!requiresReview(question)) continue
                if ((submission.id to question.id) in reviewed) continue
                val studentAnswer = answers[question.id.toString()]?.let(::answerText) ?: ""
                items += TeacherReviewQueueItemPayload(
                    submissionId = submission.id.toString(),
                    examId = exam.clientId ?: exam.id.toString(),
                    studentId = submission.userId.toString(),
                    studentName = students[submission.userId]?.name ?: "Student",
                    questionId = question.id.toString(),
                    questionText = question.text,
                    studentAnswer = studentAnswer,
                    correctAnswer = question.correctAnswer ?: question.matchingPairs ?: "",
                    suggestedMark = if (studentAnswer.isBlank()) 0 else question.points,
                    maxMark = question.points,
                    requiresTeacherReview = true,
                    requiresExplanation = question.requiresExplanation || question.qType == QuestionType.ESSAY,
                    cbcStrand = question.cbcStrandTag,
                )
            }
        }
        return items
    }

    /**
     * Idempotent per (submissionId, questionId). The award is applied as a delta
     * against the previous mark so re-review cannot inflate the submission score,
     * and the stored grading detail is updated so results stay consistent.
     */
    @Transactional
    fun submitReviewMark(teacher: UserEntity, submissionIdRaw: String, questionIdRaw: String, mark: Int) {
        examService.requireTeacher(teacher)
        val submissionId = parseUuid(submissionIdRaw, "submissionId")
        val questionId = parseUuid(questionIdRaw, "questionId")
        val submission = submissionRepository.findById(submissionId).orElse(null)
            ?: throw notFound("Submission not found")
        val exam = examRepository.findById(submission.examId).orElse(null)
            ?: throw notFound("Exam not found")
        if (exam.createdBy != teacher.id) {
            throw ApiException(ApiErrorCode.FORBIDDEN, "Not your exam")
        }
        val question = questionRepository.findById(questionId).orElse(null)
            ?: throw notFound("Question not found")
        if (question.examId != exam.id) {
            throw invalidArgument("Question does not belong to the submission's exam")
        }

        val safeMark = mark.coerceIn(0, question.points.coerceAtLeast(0))
        val existing = reviewMarkRepository.findBySubmissionIdAndQuestionId(submissionId, questionId)
        val previous = existing?.mark ?: 0
        val entity = existing ?: ExamReviewMarkEntity().apply {
            this.submissionId = submissionId
            this.questionId = questionId
        }
        entity.mark = safeMark
        entity.reviewedBy = teacher.id
        entity.reviewedAt = clock.instant()
        reviewMarkRepository.saveAndFlush(entity)

        val newScore = (submission.score + (safeMark - previous)).coerceIn(0, submission.totalPoints)
        submission.score = newScore
        submission.percentage =
            if (submission.totalPoints > 0) round(newScore * 100.0 / submission.totalPoints).toInt() else 0
        submission.questionResults =
            updateStoredResult(submission.questionResults, questionId, safeMark, question)
        submissionRepository.saveAndFlush(submission)
    }

    @Transactional(readOnly = true)
    fun remediation(teacher: UserEntity, examId: String): List<RemediationAssignmentPayload> {
        examService.requireTeacher(teacher)
        val exam = examService.requireOwnedExam(teacher, examId)
        return remediationRepository.findAllByExamIdOrderByCbcStrandAsc(exam.id).map {
            RemediationAssignmentPayload(
                cbcStrand = it.cbcStrand,
                action = it.action,
                assignedBy = it.assignedBy.toString(),
                assignedAt = it.assignedAt.toEpochMilli(),
            )
        }
    }

    /** Replace-all and idempotent: the body is the complete set for the exam. */
    @Transactional
    fun saveRemediations(teacher: UserEntity, examId: String, request: RemediationSavePayload) {
        examService.requireTeacher(teacher)
        val exam = examService.requireOwnedExam(teacher, examId)
        val byStrand = LinkedHashMap<String, String>()
        for (assignment in request.assignments) {
            val strand = assignment.cbcStrand.trim()
            if (strand.isEmpty()) throw invalidArgument("cbcStrand is required")
            val action = assignment.action.trim().uppercase()
            if (action !in ACTIONS) throw invalidArgument("Unknown remediation action: " + assignment.action)
            byStrand[strand] = action
        }
        remediationRepository.deleteAllByExamId(exam.id)
        remediationRepository.flush()
        val now = clock.instant()
        byStrand.forEach { (strand, action) ->
            remediationRepository.save(
                ExamRemediationEntity().apply {
                    this.examId = exam.id
                    this.cbcStrand = strand
                    this.action = action
                    assignedBy = teacher.id
                    assignedAt = now
                }
            )
        }
        remediationRepository.flush()
    }

    // ------------------------------------------------------------ internals

    private data class StoredResult(val questionId: String, val isCorrect: Boolean, val pointsEarned: Int)

    private data class Marked(val pointsEarned: Int, val isCorrect: Boolean)

    private fun reviewsFor(submissions: List<ExamSubmissionEntity>): Map<Pair<UUID, UUID>, ExamReviewMarkEntity> {
        if (submissions.isEmpty()) return emptyMap()
        return reviewMarkRepository.findAllBySubmissionIdIn(submissions.map { it.id })
            .associateBy { it.submissionId to it.questionId }
    }

    private fun breakdown(
        submission: ExamSubmissionEntity,
        questions: List<ExamQuestionEntity>,
        reviews: Map<Pair<UUID, UUID>, ExamReviewMarkEntity>,
    ): List<QuestionGradingDetailPayload> {
        val stored = storedResults(submission.questionResults).associateBy { it.questionId }
        val answers = answers(submission)
        return questions.map { question ->
            val id = question.id.toString()
            val review = reviews[submission.id to question.id]
            val marked = when {
                review != null -> Marked(review.mark, review.mark >= question.points)
                stored[id] != null -> Marked(stored[id]!!.pointsEarned, stored[id]!!.isCorrect)
                else -> autoGrader.grade(question, answers[id])
                    .let { Marked(it.pointsEarned, it.isCorrect) }
            }
            QuestionGradingDetailPayload(
                questionId = id,
                questionText = question.text,
                isCorrect = marked.isCorrect,
                scoreAwarded = marked.pointsEarned,
                pointsPossible = question.points,
                requiresExplanation = question.qType == QuestionType.ESSAY || question.requiresExplanation,
                isKeyQuestion = question.isKeyQuestion,
                cbcStrand = question.cbcStrandTag ?: question.topic,
            )
        }
    }

    private fun weakAreas(breakdowns: List<List<QuestionGradingDetailPayload>>): List<WeakAreaPayload> {
        val earned = mutableMapOf<String, Int>()
        val possible = mutableMapOf<String, Int>()
        val affected = mutableMapOf<String, MutableSet<Int>>()
        breakdowns.forEachIndexed { index, list ->
            list.forEach { item ->
                val strand = item.cbcStrand?.takeIf { it.isNotBlank() } ?: "General"
                possible[strand] = (possible[strand] ?: 0) + item.pointsPossible
                earned[strand] = (earned[strand] ?: 0) + item.scoreAwarded
                if (item.scoreAwarded < item.pointsPossible) {
                    affected.getOrPut(strand) { mutableSetOf() }.add(index)
                }
            }
        }
        return possible.entries
            .filter { it.value > 0 }
            .map { (strand, total) ->
                val mastery = ((earned[strand] ?: 0).toDouble() / total).coerceIn(0.0, 1.0)
                val severity = when {
                    mastery < 0.3 -> "CRITICAL"
                    mastery < 0.5 -> "MODERATE"
                    else -> "MINOR"
                }
                val action = when {
                    mastery < 0.3 -> "EXTRA_LESSON"
                    mastery < 0.5 -> "PRACTICE_EXERCISES"
                    else -> "MONITOR_ONLY"
                }
                WeakAreaPayload(
                    cbcStrand = strand,
                    masteryLevel = round2(mastery),
                    severity = severity,
                    affectedStudents = affected[strand]?.size ?: 0,
                    recommendedAction = action,
                )
            }
            .sortedBy { it.masteryLevel }
    }

    private fun keyQuestionsSummary(
        exam: ExamEntity,
        questions: List<ExamQuestionEntity>,
        submissions: List<ExamSubmissionEntity>,
    ): KeyQuestionsSummaryPayload {
        val stats = questions.filter { it.isKeyQuestion }.map { question ->
            var correct = 0
            var incorrect = 0
            var skipped = 0
            submissions.forEach { submission ->
                val node = answers(submission)[question.id.toString()]
                when {
                    node == null || node.isNull -> skipped++
                    autoGrader.grade(question, node).isCorrect -> correct++
                    else -> incorrect++
                }
            }
            val answered = correct + incorrect
            QuestionStatsPayload(
                questionId = question.id.toString(),
                questionText = question.text,
                cbcStrand = question.cbcStrandTag,
                correctCount = correct,
                incorrectCount = incorrect,
                skipCount = skipped,
                masteryPercentage = if (answered > 0) round2(correct.toDouble() / answered) else 0.0,
                flaggedByTeacher = true,
            )
        }
        return KeyQuestionsSummaryPayload(
            examId = exam.clientId ?: exam.id.toString(),
            className = className(exam),
            totalStudents = submissions.size,
            questions = stats,
        )
    }

    private fun className(exam: ExamEntity): String {
        val classId = exam.classId
        if (classId != null) {
            val name = classRepository.findById(classId).orElse(null)?.name
            if (!name.isNullOrBlank()) return name
        }
        return if (exam.gradeLevel != null) exam.subject + " | Grade " + exam.gradeLevel else exam.subject
    }

    private fun requiresReview(question: ExamQuestionEntity): Boolean =
        question.qType == QuestionType.ESSAY || question.requiresExplanation

    private fun updateStoredResult(
        json: String?,
        questionId: UUID,
        mark: Int,
        question: ExamQuestionEntity,
    ): String {
        val array = mapper.createArrayNode()
        var found = false
        val existing = if (json.isNullOrBlank()) null else runCatching { mapper.readTree(json) }.getOrNull()
        if (existing != null && existing.isArray) {
            for (item in existing) {
                val id = item.get("questionId")?.asString()
                val obj = mapper.createObjectNode()
                obj.put("questionId", id)
                if (id == questionId.toString()) {
                    found = true
                    obj.put("isCorrect", mark >= question.points)
                    obj.put("pointsEarned", mark)
                    obj.put("reviewed", true)
                } else {
                    obj.put("isCorrect", item.get("isCorrect")?.asBoolean() ?: false)
                    obj.put("pointsEarned", item.get("pointsEarned")?.intValue() ?: 0)
                    item.get("reviewed")?.let { obj.put("reviewed", it.asBoolean()) }
                }
                array.add(obj)
            }
        }
        if (!found) {
            val obj = mapper.createObjectNode()
            obj.put("questionId", questionId.toString())
            obj.put("isCorrect", mark >= question.points)
            obj.put("pointsEarned", mark)
            obj.put("reviewed", true)
            array.add(obj)
        }
        return mapper.writeValueAsString(array)
    }

    private fun storedResults(json: String?): List<StoredResult> {
        if (json.isNullOrBlank()) return emptyList()
        val node = runCatching { mapper.readTree(json) }.getOrNull() ?: return emptyList()
        if (!node.isArray) return emptyList()
        return (0 until node.size()).mapNotNull { i ->
            val item = node.get(i)
            val id = item.get("questionId")?.asString() ?: return@mapNotNull null
            StoredResult(
                questionId = id,
                isCorrect = item.get("isCorrect")?.asBoolean() ?: false,
                pointsEarned = item.get("pointsEarned")?.intValue() ?: 0,
            )
        }
    }

    private fun answers(submission: ExamSubmissionEntity): Map<String, JsonNode> {
        val json = submission.answers ?: return emptyMap()
        val node = runCatching { mapper.readTree(json) }.getOrNull() ?: return emptyMap()
        if (!node.isObject) return emptyMap()
        return node.properties().associate { it.key to it.value }
    }

    private fun answerText(node: JsonNode): String = when {
        node.isValueNode -> node.asString()
        node.isArray -> (0 until node.size()).joinToString(", ") { node.get(it).asString() }
        node.isObject -> node.properties().joinToString(", ") { it.key + ": " + it.value.asString() }
        else -> node.toString()
    }

    private fun median(sorted: List<Int>): Double {
        if (sorted.isEmpty()) return 0.0
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) {
            sorted[middle].toDouble()
        } else {
            (sorted[middle - 1] + sorted[middle]) / 2.0
        }
    }

    private fun round2(value: Double): Double = round(value * 100.0) / 100.0

    private fun parseUuid(raw: String, field: String): UUID =
        runCatching { UUID.fromString(raw) }.getOrNull()
            ?: throw invalidArgument(field + " is not a valid identifier")

    private companion object {
        val ACTIONS = setOf("PRACTICE_EXERCISES", "EXTRA_LESSON", "LEARNING_LAB", "MONITOR_ONLY")
    }
}
