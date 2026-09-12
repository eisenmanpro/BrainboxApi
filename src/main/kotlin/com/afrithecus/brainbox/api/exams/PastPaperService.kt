package com.afrithecus.brainbox.api.exams

import com.afrithecus.brainbox.api.common.error.invalidArgument
import com.afrithecus.brainbox.api.common.error.notFound
import com.afrithecus.brainbox.api.exams.admin.ExamAuthoringService
import com.afrithecus.brainbox.api.exams.entity.ExamEntity
import com.afrithecus.brainbox.api.exams.entity.ExamSubmissionEntity
import com.afrithecus.brainbox.api.exams.model.ExamScope
import com.afrithecus.brainbox.api.exams.model.ExamStatus
import com.afrithecus.brainbox.api.exams.model.ExamType
import com.afrithecus.brainbox.api.exams.repository.ExamRepository
import com.afrithecus.brainbox.api.exams.repository.ExamSubmissionRepository
import com.afrithecus.brainbox.api.exams.web.DocumentItem
import com.afrithecus.brainbox.api.exams.web.ExamContentPayload
import com.afrithecus.brainbox.api.exams.web.ExamContentQuestionPayload
import com.afrithecus.brainbox.api.exams.web.ExamCoverPayload
import com.afrithecus.brainbox.api.exams.web.ExamSectionPayload
import com.afrithecus.brainbox.api.exams.web.MarkingSchemePayload
import com.afrithecus.brainbox.api.exams.web.PastPaperAttemptRequest
import com.afrithecus.brainbox.api.identity.repository.SchoolRepository
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * Past-paper discovery and self-graded attempt recording (doc 02 §5). Lists are
 * scope-filtered; attempts are idempotent per (user, exam) so client replays
 * cannot double-record. Client-computed scores are stored as-is for v1
 * self-grading posture (doc 02 §5.3), explicitly flagged for later server-side
 * analytics migration.
 */
@Service
class PastPaperService(
    private val examRepository: ExamRepository,
    private val submissionRepository: ExamSubmissionRepository,
    private val userRepository: UserRepository,
    private val authoringService: ExamAuthoringService,
    private val schoolRepository: SchoolRepository,
    private val clock: Clock,
) {

    @Transactional(readOnly = true)
    fun list(userId: UUID, subject: String?): List<DocumentItem> =
        visiblePastPapers(userId)
            .filter { subject == null || it.subject.equals(subject, ignoreCase = true) }
            .sortedBy { it.title.lowercase() }
            .map(::toDocumentItem)

    @Transactional(readOnly = true)
    fun search(userId: UUID, query: String): List<DocumentItem> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        return visiblePastPapers(userId)
            .filter { it.title.contains(q, ignoreCase = true) || it.subject.contains(q, ignoreCase = true) }
            .sortedBy { it.title.lowercase() }
            .map(::toDocumentItem)
    }

    @Transactional
    fun recordAttempt(userId: UUID, examIdRaw: String, request: PastPaperAttemptRequest) {
        val exam = examForUser(userId, examIdRaw)
        if (exam.examType != ExamType.PAST_PAPER) {
            throw invalidArgument("Only past papers accept self-graded attempts")
        }
        val existing = submissionRepository.findByUserIdAndExamId(userId, exam.id)
        val submission = (existing ?: ExamSubmissionEntity().apply {
            this.examId = exam.id
            this.userId = userId
        }).apply {
            score = request.score
            totalPoints = request.totalPoints
            percentage = request.percentage
            submittedAt = request.submittedAt?.let(Instant::ofEpochMilli) ?: clock.instant()
        }
        submissionRepository.save(submission)
    }

    @Transactional(readOnly = true)
    fun content(userId: UUID, examIdRaw: String): ExamContentPayload {
        val exam = examForUser(userId, examIdRaw)
        // Keys are embedded in markingScheme, so this must never serve a live
        // digital exam (doc 02 §4.2 obligation 3).
        if (exam.examType != ExamType.PAST_PAPER) throw notFound("Exam not found")
        val user = userRepository.findById(userId).orElseThrow { notFound("User not found") }
        val schoolName = exam.schoolId?.let { schoolRepository.findById(it).map { school -> school.name }.orElse("") }
            ?: user.schoolId?.let { schoolRepository.findById(it).map { school -> school.name }.orElse("") }
            ?: ""
        val questions = authoringService.questionsOf(exam.id).mapIndexed { index, question ->
            val payload = authoringService.toQuestionPayload(question, includeKeys = true)
            ExamContentQuestionPayload(
                id = payload.id,
                text = payload.text,
                type = payload.type,
                options = payload.options,
                correctAnswer = payload.correctAnswer ?: "",
                explanation = payload.explanation ?: "",
                points = payload.points,
                difficulty = payload.difficulty,
                matchingPairs = payload.matchingPairs,
                number = index + 1,
                topic = payload.topic ?: "",
                subtopic = payload.subtopic,
            )
        }
        val totalMarks = questions.sumOf { it.points }
        return ExamContentPayload(
            examId = exam.id.toString(),
            cover = ExamCoverPayload(
                schoolName = schoolName,
                studentName = user.name,
                examId = exam.id.toString(),
                time = formatDuration(exam.durationMinutes),
                questionCount = questions.size,
                year = exam.examYear ?: 0,
                mcp = exam.isMcp,
                subject = exam.subject,
            ),
            sections = listOf(ExamSectionPayload(type = "STANDARD", instructions = null, questions = questions)),
            markingScheme = MarkingSchemePayload(
                questionAnswers = questions.associate { it.id to it.correctAnswer },
                questionMarks = questions.associate { it.id to it.points },
                totalMarks = totalMarks,
                passingScore = totalMarks / 2 + 1,
            ),
        )
    }

    // ------------------------------------------------------------ internals

    private fun formatDuration(minutes: Int): String {
        val hours = minutes / 60
        val mins = minutes % 60
        return buildString {
            if (hours > 0) append(hours).append("hr")
            if (mins > 0) {
                if (hours > 0) append(" ")
                append(mins).append("min")
            }
        }.ifEmpty { minutes.toString() + "min" }
    }

    private fun visiblePastPapers(userId: UUID): List<ExamEntity> {
        val user = userRepository.findById(userId).orElseThrow { notFound("User not found") }
        return examRepository.findAllByStatus(ExamStatus.PUBLISHED)
            .filter { it.examType == ExamType.PAST_PAPER }
            .filter { exam ->
                when (exam.scope) {
                    ExamScope.GLOBAL -> true
                    ExamScope.SCHOOL, ExamScope.SCHOOL_GRADE_CLASS ->
                        user.schoolId != null && exam.schoolId != null && exam.schoolId == user.schoolId
                }
            }
    }

    private fun examForUser(userId: UUID, examIdRaw: String): ExamEntity {
        val id = runCatching { UUID.fromString(examIdRaw) }.getOrNull()
            ?: throw invalidArgument("exam id is not a valid identifier")
        val exam = examRepository.findById(id).orElse(null) ?: throw notFound("Exam not found")
        val user = userRepository.findById(userId).orElseThrow { notFound("User not found") }
        val visible = when (exam.scope) {
            ExamScope.GLOBAL -> true
            ExamScope.SCHOOL, ExamScope.SCHOOL_GRADE_CLASS ->
                user.schoolId != null && exam.schoolId != null && exam.schoolId == user.schoolId
        }
        if (!visible || exam.status != ExamStatus.PUBLISHED) throw notFound("Exam not found")
        return exam
    }

    private fun toDocumentItem(exam: ExamEntity) = DocumentItem(
        id = exam.id.toString(),
        title = exam.title,
        subject = exam.subject,
        durationMinutes = exam.durationMinutes,
        questionCount = exam.questionCount,
        examYear = exam.examYear,
        isMcp = exam.isMcp,
        iconUrl = exam.coverImageUrl,
    )
}
