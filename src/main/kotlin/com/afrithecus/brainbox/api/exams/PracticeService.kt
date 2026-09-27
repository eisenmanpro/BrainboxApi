package com.afrithecus.brainbox.api.exams

import com.afrithecus.brainbox.api.common.error.ApiException
import com.afrithecus.brainbox.api.common.error.ApiErrorCode
import com.afrithecus.brainbox.api.common.error.conflict
import com.afrithecus.brainbox.api.common.error.invalidArgument
import com.afrithecus.brainbox.api.common.error.notFound
import com.afrithecus.brainbox.api.exams.entity.ExamEntity
import com.afrithecus.brainbox.api.exams.entity.ExamQuestionEntity
import com.afrithecus.brainbox.api.exams.model.ExamScope
import com.afrithecus.brainbox.api.exams.model.ExamStatus
import com.afrithecus.brainbox.api.exams.model.ExamType
import com.afrithecus.brainbox.api.exams.repository.ExamQuestionRepository
import com.afrithecus.brainbox.api.exams.repository.ExamRepository
import com.afrithecus.brainbox.api.exams.repository.ExamSubmissionRepository
import com.afrithecus.brainbox.api.exams.web.PracticeGenerateRequest
import com.afrithecus.brainbox.api.exams.web.PracticePaperPayload
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.model.CurrentUser
import com.afrithecus.brainbox.api.identity.model.Role
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import com.afrithecus.brainbox.api.parent.ParentPortalService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID
import kotlin.random.Random

/**
 * Personalised practice (§B7): a learner asks for practice on a subject and gets a paper
 * assembled from content a teacher already reviewed, scoped to them alone.
 *
 * The paper is a real `exams` row of type PRACTICE_PAPER with `scope = PERSONAL` and
 * `ownerUserId = learner`, so the existing session, grading and result machinery serves it
 * unchanged while the catalog, the practice-paper browse and other learners never see it.
 *
 * Questions are sampled from published practice papers for the learner's grade and subject;
 * the target difficulty is the one the learner asked for, or the difficulty their recent
 * practice results suggest. Nothing is invented: with no reviewed content for that subject
 * the request is refused with a clear reason instead of a fabricated paper.
 */
@Service
class PracticeService(
    private val exams: ExamRepository,
    private val questions: ExamQuestionRepository,
    private val submissions: ExamSubmissionRepository,
    private val users: UserRepository,
    private val portal: ParentPortalService,
    private val catalog: ExamCatalogService,
    private val clock: Clock,
) {

    /** Generates and returns a personal practice paper for the calling learner. */
    @Transactional
    fun generate(current: CurrentUser, request: PracticeGenerateRequest): PracticePaperPayload {
        val learner = requireLearner(current)
        val subject = request.subject.trim().takeIf { it.isNotEmpty() }
            ?: throw invalidArgument("subject is required")
        val grade = learner.gradeLevel?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw invalidArgument("Your account has no grade level to practise for")
        val count = (request.questionCount ?: DEFAULT_QUESTIONS).coerceIn(1, MAX_QUESTIONS)

        val candidates = candidateQuestions(subject, grade, request.difficulty, learner)
        if (candidates.isEmpty()) {
            throw conflict("No reviewed practice content for $subject in $grade yet")
        }

        val difficulty = request.difficulty?.coerceIn(1, 5) ?: suggestedDifficulty(learner.id)
        val chosen = sample(candidates, count, difficulty, learner.id)
        val title = (request.topic?.trim()?.takeIf { it.isNotEmpty() } ?: subject) + " practice"

        val paper = exams.save(
            ExamEntity().apply {
                this.title = title
                this.subject = subject
                this.examType = ExamType.PRACTICE_PAPER
                this.scope = ExamScope.PERSONAL
                this.ownerUserId = learner.id
                this.schoolId = learner.schoolId
                this.gradeLevel = parseGrade(grade)
                this.durationMinutes = maxOf(MINUTES_PER_QUESTION * chosen.size, MIN_PAPER_MINUTES)
                this.questionCount = chosen.size
                this.difficulty = difficulty
                this.totalPoints = chosen.sumOf { it.points }
                this.status = ExamStatus.PUBLISHED
                this.createdBy = learner.id
                // exams.client_id is varchar(80): keep the generated code well inside it
                this.clientId = "practice_" + UUID.randomUUID()
            }
        )

        chosen.forEachIndexed { index, source ->
            questions.save(
                ExamQuestionEntity().apply {
                    examId = paper.id
                    text = source.text
                    qType = source.qType
                    options = source.options
                    correctAnswer = source.correctAnswer
                    explanation = source.explanation
                    points = source.points
                    this.difficulty = source.difficulty
                    matchingPairs = source.matchingPairs
                    topic = source.topic
                    subtopic = source.subtopic
                    orderIndex = index
                    clientId = paper.id.toString() + "_q" + (index + 1)
                    sectionId = source.sectionId
                    cbcStrandTag = source.cbcStrandTag
                    isKeyQuestion = source.isKeyQuestion
                    requiresExplanation = source.requiresExplanation
                    figureSvg = source.figureSvg
                }
            )
        }

        return paper.toPayload()
    }

    /** The learner's own practice papers, newest first. */
    @Transactional(readOnly = true)
    fun history(current: CurrentUser): List<PracticePaperPayload> = forLearner(current.userId)

    /** A teacher reading a learner's practice papers in their school. */
    @Transactional(readOnly = true)
    fun forStudent(current: CurrentUser, studentIdRaw: String): List<PracticePaperPayload> {
        val staff = users.findById(current.userId).orElseThrow { notFound("User not found") }
        if (staff.role != Role.TEACHER && staff.role != Role.ADMIN) {
            throw ApiException(ApiErrorCode.FORBIDDEN, "Only a teacher can read a learner's practice papers")
        }
        val student = users.findById(parseId(studentIdRaw, "studentId")).orElse(null)
            ?: throw notFound("Student not found")
        if (staff.role != Role.ADMIN && (staff.schoolId == null || staff.schoolId != student.schoolId)) {
            throw notFound("Student not found")
        }
        return forLearner(student.id)
    }

    /** A guardian reading their linked child's practice papers. */
    @Transactional(readOnly = true)
    fun forChild(current: CurrentUser, childIdRaw: String): List<PracticePaperPayload> {
        val child = portal.requireLinkedChild(current, childIdRaw)
        return forLearner(child.id)
    }

    // ---------------------------------------------------------------- internals

    private fun forLearner(learnerId: UUID): List<PracticePaperPayload> =
        exams.findAllByOwnerUserIdAndScopeOrderByCreatedAtDesc(learnerId, ExamScope.PERSONAL)
            .map { it.toPayload() }

    /**
     * Questions from reviewed practice papers of the same subject and grade that the learner
     * is allowed to see. A requested difficulty widens by one step either way, because a
     * single-difficulty pool is often too thin to fill a paper.
     */
    private fun candidateQuestions(
        subject: String,
        grade: String,
        difficulty: Int?,
        learner: UserEntity,
    ): List<ExamQuestionEntity> {
        val gradeValue = parseGrade(grade)
        val sources = exams.findAllByStatus(ExamStatus.PUBLISHED)
            .filter { it.examType == ExamType.PRACTICE_PAPER }
            .filter { catalog.isVisible(it, learner) }
            .filter { it.subject.equals(subject, ignoreCase = true) }
            .filter { it.gradeLevel == null || gradeValue == null || it.gradeLevel == gradeValue }
        if (sources.isEmpty()) return emptyList()
        val rows = questions.findAllByExamIdIn(sources.map { it.id })
        if (difficulty == null) return rows
        val target = difficulty.coerceIn(1, 5)
        val inRange = rows.filter { kotlin.math.abs(it.difficulty - target) <= 1 }
        return inRange.ifEmpty { rows }
    }

    /** Prefer questions closest to the target difficulty, then vary the choice per learner. */
    private fun sample(
        candidates: List<ExamQuestionEntity>,
        count: Int,
        difficulty: Int,
        learnerId: UUID,
    ): List<ExamQuestionEntity> {
        if (candidates.size <= count) return candidates.shuffled(Random(learnerId.hashCode()))
        val ordered = candidates.sortedWith(
            compareBy({ kotlin.math.abs(it.difficulty - difficulty) }, { it.orderIndex }, { it.id })
        )
        // Take a slightly wider band than needed, then shuffle that band so two requests
        // for the same topic are not identical papers.
        val band = ordered.take((count * 2).coerceAtMost(ordered.size))
        return band.shuffled(Random(learnerId.hashCode() + clock.millis() / DAY_MILLIS)).take(count)
    }

    /**
     * The difficulty to aim at when the learner did not choose one: the average of their
     * recent practice difficulty, moved up when they scored well and down when they did not.
     */
    private fun suggestedDifficulty(learnerId: UUID): Int {
        val recent = exams.findAllByOwnerUserIdAndScopeOrderByCreatedAtDesc(learnerId, ExamScope.PERSONAL)
            .take(RECENT_FOR_DIFFICULTY)
        if (recent.isEmpty()) return DEFAULT_DIFFICULTY
        val base = recent.map { it.difficulty }.average()
        val scores = recent.mapNotNull { exam ->
            submissions.findAllByExamId(exam.id).maxOfOrNull { it.percentage }
        }
        if (scores.isEmpty()) return base.toInt().coerceIn(1, 5)
        val average = scores.average()
        val shift = when {
            average >= STRONG_SCORE -> 1
            average < WEAK_SCORE -> -1
            else -> 0
        }
        return (base + shift).toInt().coerceIn(1, 5)
    }

    private fun requireLearner(current: CurrentUser): UserEntity {
        val user = users.findById(current.userId).orElseThrow { notFound("User not found") }
        if (user.role != Role.STUDENT) {
            throw ApiException(ApiErrorCode.FORBIDDEN, "Practice papers are generated for a learner account")
        }
        return user
    }

    /** "Grade 6" and "6" both mean grade 6; an unknown string leaves the grade unscoped. */
    private fun parseGrade(raw: String?): Int? = raw?.filter { it.isDigit() }?.takeIf { it.isNotEmpty() }?.toIntOrNull()

    private fun parseId(raw: String, field: String): UUID =
        runCatching { UUID.fromString(raw) }.getOrNull() ?: throw invalidArgument("$field is not a valid identifier")

    private fun ExamEntity.toPayload(): PracticePaperPayload {
        val attempts = submissions.findAllByExamId(id)
        val topic = questions.findAllByExamIdOrderByOrderIndexAsc(id)
            .mapNotNull { it.topic }
            .groupingBy { it }
            .eachCount()
            .maxByOrNull { it.value }
            ?.key
        return PracticePaperPayload(
            examId = id.toString(),
            title = title,
            subject = subject,
            topic = topic,
            questionCount = questionCount,
            difficulty = difficulty,
            durationMinutes = durationMinutes,
            totalPoints = totalPoints,
            createdAt = createdAt.toEpochMilli(),
            attempts = attempts.size,
            bestPercentage = attempts.maxOfOrNull { it.percentage },
        )
    }

    private companion object {
        const val DEFAULT_QUESTIONS = 5
        const val MAX_QUESTIONS = 20
        const val MINUTES_PER_QUESTION = 3
        const val MIN_PAPER_MINUTES = 15
        const val DEFAULT_DIFFICULTY = 3
        const val RECENT_FOR_DIFFICULTY = 3
        const val STRONG_SCORE = 80
        const val WEAK_SCORE = 50
        const val DAY_MILLIS = 86_400_000L
    }
}
