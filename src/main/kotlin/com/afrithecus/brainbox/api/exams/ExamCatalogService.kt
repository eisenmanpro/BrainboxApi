package com.afrithecus.brainbox.api.exams

import com.afrithecus.brainbox.api.classes.repository.ClassMembershipRepository
import com.afrithecus.brainbox.api.common.error.notFound
import com.afrithecus.brainbox.api.exams.admin.ExamAuthoringService
import com.afrithecus.brainbox.api.exams.entity.ExamEntity
import com.afrithecus.brainbox.api.exams.model.ExamScope
import com.afrithecus.brainbox.api.exams.model.ExamStatus
import com.afrithecus.brainbox.api.exams.model.ExamType
import com.afrithecus.brainbox.api.exams.model.SessionStatus
import com.afrithecus.brainbox.api.exams.repository.ExamRepository
import com.afrithecus.brainbox.api.exams.repository.ExamSessionRepository
import com.afrithecus.brainbox.api.exams.repository.ExamSubmissionRepository
import com.afrithecus.brainbox.api.exams.web.ExamCard
import com.afrithecus.brainbox.api.exams.web.ExamDetail
import com.afrithecus.brainbox.api.exams.web.ExamSummary
import com.afrithecus.brainbox.api.exams.web.HubState
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Student-facing exam catalog (doc 02 §2): hub state/cards, listings and
 * details. Everything is scope-filtered (GLOBAL / SCHOOL / SCHOOL_GRADE_CLASS)
 * against the requesting user's school, and question payloads are key-stripped.
 */
@Service
class ExamCatalogService(
    private val examRepository: ExamRepository,
    private val sessionRepository: ExamSessionRepository,
    private val submissionRepository: ExamSubmissionRepository,
    private val authoringService: ExamAuthoringService,
    private val userRepository: UserRepository,
    private val membershipRepository: ClassMembershipRepository,
) {

    @Transactional(readOnly = true)
    fun hubState(userId: UUID): HubState {
        val ctx = contextOf(userId)
        return HubState(
            availableCount = ctx.available.size,
            inProgressCount = ctx.sessions.values.count { it.status == SessionStatus.IN_PROGRESS },
            completedCount = ctx.submissions.size,
            quizzesCount = ctx.visible.filter { it.examType == ExamType.QUIZ }.size,
            savedCount = ctx.sessions.values.count { it.status == SessionStatus.IN_PROGRESS },
            practicePapersCount = ctx.visible.filter { it.examType == ExamType.PRACTICE_PAPER }.size,
        )
    }

    @Transactional(readOnly = true)
    fun listByTab(userId: UUID, tab: String): List<ExamCard> {
        val ctx = contextOf(userId)
        return when (tab) {
            "available" -> ctx.available.map { it.toCard("AVAILABLE") }
            "in_progress", "saved" -> ctx.sessions.values
                .filter { it.status == SessionStatus.IN_PROGRESS }
                .mapNotNull { s -> examRepository.findById(s.examId).orElse(null)?.let { it.toCard("IN_PROGRESS") } }
            "quizzes" -> ctx.visible.filter { it.examType == ExamType.QUIZ }.map { it.toCard("QUIZ") }
            "practice_papers" -> ctx.visible.filter { it.examType == ExamType.PRACTICE_PAPER }.map { it.toCard("AVAILABLE", isPractice = true) }
            "completed", "analytics" -> ctx.submissions.map { s ->
                val exam = examRepository.findById(s.examId).orElse(null)
                    ?: return@map null
                exam.toCard("COMPLETED", average = s.percentage, completedAt = s.submittedAt.toEpochMilli())
            }.filterNotNull()
            else -> throw com.afrithecus.brainbox.api.common.error.invalidArgument(
                "tab must be one of available|in_progress|completed|quizzes|saved|analytics|practice_papers"
            )
        }
    }

    @Transactional(readOnly = true)
    fun allCards(userId: UUID): List<ExamCard> =
        listOf("available", "in_progress", "quizzes", "practice_papers", "completed")
            .flatMap { listByTab(userId, it) }
            .distinctBy { it.id + ":" + it.status }

    @Transactional(readOnly = true)
    fun listExams(userId: UUID, subject: String?, difficulty: Int?): List<ExamSummary> {
        val user = loadUser(userId)
        val exams = visiblePublished(user)
            .filter { subject == null || it.subject.equals(subject, ignoreCase = true) }
            .filter { difficulty == null || it.difficulty == difficulty }
            .sortedBy { it.title.lowercase() }
        return exams.map { toSummary(it) }
    }

    @Transactional(readOnly = true)
    fun detail(userId: UUID, examIdRaw: String): ExamDetail {
        val user = loadUser(userId)
        val exam = examRepository.findById(parseId(examIdRaw)).orElse(null)
            ?: throw notFound("Exam not found")
        if (exam.status != ExamStatus.PUBLISHED || !isVisible(exam, user)) {
            throw notFound("Exam not found")
        }
        val questions = authoringService.questionsOf(exam.id)
        return ExamDetail(
            id = exam.id.toString(),
            title = exam.title,
            subject = exam.subject,
            durationMinutes = exam.durationMinutes,
            questionCount = exam.questionCount,
            difficulty = exam.difficulty,
            status = exam.status.name,
            questions = questions.map { authoringService.toQuestionPayload(it, includeKeys = false) },
            createdBy = exam.createdBy.toString(),
            createdAt = exam.createdAt.toEpochMilli(),
            isPublished = true,
            averageScore = submissionsOf(exam.id).map { it.percentage }.average().toInt(),
            studentsTaken = submissionsOf(exam.id).size,
        )
    }

    // ------------------------------------------------------------- internals

    private data class Context(
        val user: UserEntity,
        val visible: List<ExamEntity>,
        val sessions: Map<UUID, com.afrithecus.brainbox.api.exams.entity.ExamSessionEntity>,
        val submissions: List<com.afrithecus.brainbox.api.exams.entity.ExamSubmissionEntity>,
    ) {
        val started = sessions.keys
        val completedExams = submissions.map { it.examId }.toSet()
        val available: List<ExamEntity> get() = visible
            .filter { it.examType == ExamType.DIGITAL || it.examType == ExamType.QUIZ }
            .filter { it.id !in started && it.id !in completedExams }
    }

    private fun contextOf(userId: UUID): Context {
        val user = loadUser(userId)
        return Context(
            user = user,
            visible = visiblePublished(user),
            sessions = sessionRepository.findAllByUserId(userId).associateBy { it.examId },
            submissions = submissionRepository.findAllByUserId(userId),
        )
    }

    private fun visiblePublished(user: UserEntity): List<ExamEntity> =
        examRepository.findAllByStatus(ExamStatus.PUBLISHED).filter { isVisible(it, user) }

    /**
     * A class-scoped exam (one authored for a specific teacher class) is only
     * visible to a student enrolled in that class; every other scope keeps the
     * existing school-level rule.
     */
    private fun isVisible(exam: ExamEntity, user: UserEntity): Boolean = when (exam.scope) {
        ExamScope.GLOBAL -> true
        ExamScope.SCHOOL, ExamScope.SCHOOL_GRADE_CLASS -> {
            val sameSchool = user.schoolId != null && exam.schoolId != null && exam.schoolId == user.schoolId
            val classId = exam.classId
            sameSchool && (classId == null || membershipRepository.findByClassIdAndStudentId(classId, user.id) != null)
        }
    }

    private fun loadUser(userId: UUID): UserEntity =
        userRepository.findById(userId).orElseThrow { notFound("User not found") }

    private fun submissionsOf(examId: UUID) = submissionRepository.findAllByExamId(examId)

    private fun toSummary(exam: ExamEntity): ExamSummary {
        val subs = submissionsOf(exam.id)
        return ExamSummary(
            id = exam.id.toString(),
            title = exam.title,
            subject = exam.subject,
            durationMinutes = exam.durationMinutes,
            questionCount = exam.questionCount,
            difficulty = exam.difficulty,
            status = exam.status.name,
            isPracticePaper = exam.examType == ExamType.PRACTICE_PAPER,
            examYear = exam.examYear,
            coverImageUrl = exam.coverImageUrl,
            averageScore = if (subs.isEmpty()) null else subs.map { it.percentage }.average().toInt(),
            studentsTaken = subs.size,
        )
    }

    private fun ExamEntity.toCard(
        cardStatus: String,
        average: Int? = null,
        completedAt: Long? = null,
        isPractice: Boolean = examType == ExamType.PRACTICE_PAPER,
    ) = ExamCard(
        id = id.toString(),
        title = title,
        subject = subject,
        durationMinutes = durationMinutes,
        questionCount = questionCount,
        difficulty = difficulty,
        status = cardStatus,
        isPracticePaper = isPractice,
        examYear = examYear,
        coverImageUrl = coverImageUrl,
        averageScore = average,
        completedAt = completedAt,
    )

    private fun parseId(raw: String): UUID =
        runCatching { UUID.fromString(raw) }.getOrNull()
            ?: throw com.afrithecus.brainbox.api.common.error.invalidArgument("exam id is not a valid identifier")
}
