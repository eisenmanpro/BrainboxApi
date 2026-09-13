package com.afrithecus.brainbox.api.traditional

import com.afrithecus.brainbox.api.classes.repository.ClassMembershipRepository
import com.afrithecus.brainbox.api.classes.repository.TeacherClassRepository
import com.afrithecus.brainbox.api.common.error.ApiErrorCode
import com.afrithecus.brainbox.api.common.error.ApiException
import com.afrithecus.brainbox.api.common.error.conflict
import com.afrithecus.brainbox.api.common.error.invalidArgument
import com.afrithecus.brainbox.api.common.error.notFound
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.model.CurrentUser
import com.afrithecus.brainbox.api.identity.model.Role
import com.afrithecus.brainbox.api.identity.model.SubRole
import com.afrithecus.brainbox.api.identity.repository.SchoolRepository
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import com.afrithecus.brainbox.api.notification.NotificationService
import com.afrithecus.brainbox.api.notification.model.NotificationPriority
import com.afrithecus.brainbox.api.notification.model.NotificationType
import com.afrithecus.brainbox.api.notification.model.NotificationUrgency
import com.afrithecus.brainbox.api.traditional.entity.TraditionalConfirmationEntity
import com.afrithecus.brainbox.api.traditional.entity.TraditionalEditPermissionEntity
import com.afrithecus.brainbox.api.traditional.entity.TraditionalEditRequestEntity
import com.afrithecus.brainbox.api.traditional.entity.TraditionalExamEntity
import com.afrithecus.brainbox.api.traditional.entity.TraditionalExamSubjectEntity
import com.afrithecus.brainbox.api.traditional.entity.TraditionalGradingConfigEntity
import com.afrithecus.brainbox.api.traditional.entity.TraditionalMarkEntity
import com.afrithecus.brainbox.api.traditional.entity.TraditionalSubjectConfigEntity
import com.afrithecus.brainbox.api.traditional.model.ExamTerm
import com.afrithecus.brainbox.api.traditional.model.TraditionalEditStatus
import com.afrithecus.brainbox.api.traditional.model.TraditionalExamStatus
import com.afrithecus.brainbox.api.traditional.model.TraditionalSubjectType
import com.afrithecus.brainbox.api.traditional.model.displayName
import com.afrithecus.brainbox.api.traditional.repository.TraditionalConfirmationRepository
import com.afrithecus.brainbox.api.traditional.repository.TraditionalEditPermissionRepository
import com.afrithecus.brainbox.api.traditional.repository.TraditionalEditRequestRepository
import com.afrithecus.brainbox.api.traditional.repository.TraditionalExamRepository
import com.afrithecus.brainbox.api.traditional.repository.TraditionalExamSubjectRepository
import com.afrithecus.brainbox.api.traditional.repository.TraditionalGradingConfigRepository
import com.afrithecus.brainbox.api.traditional.repository.TraditionalMarkRepository
import com.afrithecus.brainbox.api.traditional.repository.TraditionalSubjectConfigRepository
import com.afrithecus.brainbox.api.traditional.web.ClassGradeAnalysisDto
import com.afrithecus.brainbox.api.traditional.web.CreateTraditionalExamRequest
import com.afrithecus.brainbox.api.traditional.web.EditPermissionDto
import com.afrithecus.brainbox.api.traditional.web.EditRequestDto
import com.afrithecus.brainbox.api.traditional.web.ExamConfirmationStatusDto
import com.afrithecus.brainbox.api.traditional.web.GradeCheckSummaryDto
import com.afrithecus.brainbox.api.traditional.web.GradingBandDto
import com.afrithecus.brainbox.api.traditional.web.GradingConfigDefaults
import com.afrithecus.brainbox.api.traditional.web.GradingConfigDto
import com.afrithecus.brainbox.api.traditional.web.MarkEntryDto
import com.afrithecus.brainbox.api.traditional.web.OverallGradingBandDto
import com.afrithecus.brainbox.api.traditional.web.PreFinalCheckSummaryDto
import com.afrithecus.brainbox.api.traditional.web.StudentDto
import com.afrithecus.brainbox.api.traditional.web.StudentGradeRowDto
import com.afrithecus.brainbox.api.traditional.web.SubjectComponentDto
import com.afrithecus.brainbox.api.traditional.web.SubjectComponentResultDto
import com.afrithecus.brainbox.api.traditional.web.SubjectConfigDto
import com.afrithecus.brainbox.api.traditional.web.TopPerformerDto
import com.afrithecus.brainbox.api.traditional.web.StrugglingStudentDto
import com.afrithecus.brainbox.api.traditional.web.TraditionalExamAnalyticsDto
import com.afrithecus.brainbox.api.traditional.web.TraditionalExamDto
import com.afrithecus.brainbox.api.traditional.web.TraditionalGradeAnalysisDto
import com.afrithecus.brainbox.api.traditional.web.TraditionalMarkDto
import com.afrithecus.brainbox.api.traditional.web.TraditionalStudentReportDto
import com.afrithecus.brainbox.api.traditional.web.TraditionalSubjectResultDto
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Traditional (paper) exam engine and student-report pipeline (docs 10/13 and
 * docs/ongoing/api_student_reports_changes.md). The Android models are the
 * source of truth for every payload shape.
 *
 * Access model (api_student_reports_changes.md §1/§4): reading results is not
 * role-gated — a student reads their own, a linked parent their child — and the
 * data gate is publication. Ownership is still enforced.
 */
@Service
class TraditionalExamService(
    private val examRepository: TraditionalExamRepository,
    private val subjectRepository: TraditionalExamSubjectRepository,
    private val markRepository: TraditionalMarkRepository,
    private val subjectConfigRepository: TraditionalSubjectConfigRepository,
    private val gradingConfigRepository: TraditionalGradingConfigRepository,
    private val gradingConfigService: TraditionalGradingConfigService,
    private val confirmationRepository: TraditionalConfirmationRepository,
    private val editRequestRepository: TraditionalEditRequestRepository,
    private val editPermissionRepository: TraditionalEditPermissionRepository,
    private val userRepository: UserRepository,
    private val schoolRepository: SchoolRepository,
    private val classMembershipRepository: ClassMembershipRepository,
    private val teacherClassRepository: TeacherClassRepository,
    private val notificationService: NotificationService,
    private val mapper: ObjectMapper,
    private val clock: Clock,
) {

    // ---------------------------------------------------------------- exams

    @Transactional(readOnly = true)
    fun listExams(current: CurrentUser, grade: String?): List<TraditionalExamDto> {
        val user = user(current)
        val exams = when (current.role) {
            Role.STUDENT -> {
                val target = grade?.takeIf { it.isNotBlank() } ?: user.gradeLevel
                publishedForSchool(user.schoolId, target)
            }
            Role.PARENT -> {
                val child = primaryChild(current)
                val target = grade?.takeIf { it.isNotBlank() } ?: child?.gradeLevel
                publishedForSchool(scopeSchoolId(current), target)
            }
            else -> {
                val all = examsForStaff(user)
                if (grade.isNullOrBlank()) all else all.filter { it.gradeLevel.equals(grade, ignoreCase = true) }
            }
        }
        return exams.sortedByDescending { it.createdAt }.map(::examDto)
    }

    @Transactional(readOnly = true)
    fun getExam(current: CurrentUser, examIdRaw: String): TraditionalExamDto {
        val exam = exam(current, examIdRaw)
        if (!visibleTo(current, exam)) throw notFound("Exam not found")
        if ((current.role == Role.STUDENT || current.role == Role.PARENT) && exam.status != TraditionalExamStatus.PUBLISHED) {
            throw ApiException(ApiErrorCode.FORBIDDEN, "Results are not published yet")
        }
        return examDto(exam)
    }

    @Transactional
    fun createExam(current: CurrentUser, request: CreateTraditionalExamRequest): TraditionalExamDto {
        requireStaffWriter(current)
        validateSubjects(request.subjects)
        val user = user(current)
        // CREATE_EXAM replays from the client outbox: storing the client id makes
        // the call idempotent and keeps the exam addressable under the id the
        // device stores locally.
        val clientExamId = request.examId?.trim()?.takeIf { it.isNotBlank() && parseUuidOrNull(it) == null }
        if (clientExamId != null) {
            examRepository.findBySchoolIdAndClientExamId(user.schoolId, clientExamId)?.let { return examDto(it) }
        }
        val exam = TraditionalExamEntity().apply {
            title = request.title.trim()
            term = request.term
            gradeLevel = request.gradeLevel.trim()
            year = request.year
            status = TraditionalExamStatus.PENDING
            maxScore = request.maxScore
            autoGenerated = request.autoGenerated
            schoolId = user.schoolId
            createdBy = user.id
            this.clientExamId = clientExamId
        }
        examRepository.save(exam)
        saveSubjects(exam.id, request.subjects)
        return examDto(exam)
    }

    @Transactional
    fun generateExams(
        current: CurrentUser,
        grade: String,
        term: ExamTerm,
        year: Int,
        subjects: List<SubjectConfigDto>,
    ): List<TraditionalExamDto> {
        // The client materialises its own term exams (and their deterministic
        // TRAD_ ids) before it reaches the network; this call only mirrors them
        // server-side so marks, confirmations and analytics can follow. Any staff
        // writer may trigger the idempotent mirror.
        requireStaffWriter(current)
        require(grade.isNotBlank()) { "grade is required" }
        validateSubjects(subjects)
        val user = user(current)
        val termNumber = term.ordinal + 1
        val sanitizedGrade = grade.filter { it.isLetterOrDigit() }
        val sessions = listOf(
            "OPENER" to "Opener Term",
            "MID" to "Mid Term",
            "END" to "End Term",
        )
        return sessions.map { (suffix, label) ->
            val clientExamId = "TRAD_" + sanitizedGrade + "_" + suffix + "_" + year + "_T" + termNumber
            val existing = examRepository.findBySchoolIdAndClientExamId(user.schoolId, clientExamId)
            if (existing != null) {
                examDto(existing)
            } else {
                val created = TraditionalExamEntity().apply {
                    title = label + " " + termNumber + " " + year + " - " + grade
                    this.term = term
                    gradeLevel = grade
                    this.year = year
                    status = TraditionalExamStatus.PENDING
                    autoGenerated = true
                    schoolId = user.schoolId
                    createdBy = user.id
                    this.clientExamId = clientExamId
                }
                examRepository.save(created)
                saveSubjects(created.id, subjects)
                examDto(created)
            }
        }
    }

    @Transactional
    fun updateStatus(current: CurrentUser, examIdRaw: String, statusRaw: String): TraditionalExamDto {
        requireCoordinator(current)
        val exam = exam(current, examIdRaw)
        val target = enumOr(TraditionalExamStatus.entries, statusRaw, "exam status")
        if (target == TraditionalExamStatus.PUBLISHED) return publish(current, examIdRaw, current.userId.toString())
        ensureTransitionAllowed(exam, target)
        exam.status = target
        examRepository.save(exam)
        return examDto(exam)
    }

    @Transactional(readOnly = true)
    fun confirmationStatus(current: CurrentUser, examIdRaw: String): ExamConfirmationStatusDto {
        requireStaffWriter(current)
        val exam = exam(current, examIdRaw)
        val tally = confirmationTally(exam.id)
        return ExamConfirmationStatusDto(
            examId = publicExamId(exam),
            totalTeachers = tally.total,
            confirmedCount = tally.confirmed,
            pendingTeachers = tally.pending,
        )
    }

    @Transactional(readOnly = true)
    fun preFinalChecks(current: CurrentUser, examIdRaw: String): PreFinalCheckSummaryDto {
        requireStaffWriter(current)
        val exam = exam(current, examIdRaw)
        val subjects = subjectRepository.findAllByExamIdOrderByOrderIndexAsc(exam.id)
        val marks = markRepository.findAllByExamId(exam.id)
        val byStudent = marks.groupBy { it.studentId }
        val students = studentsForExam(exam)
        val checks = linkedMapOf<String, GradeCheckSummaryDto>()
        for (student in students) {
            val key = student.gradeLevel ?: exam.gradeLevel
            val row = checks[key] ?: GradeCheckSummaryDto(key, 0, 0, 0, 0)
            val recorded = byStudent[student.id]?.map { it.subjectId }?.toSet() ?: emptySet()
            val expected = subjects.map { it.subjectId }
            val complete = expected.isNotEmpty() && expected.all { it in recorded }
            val missing = recorded.intersect(expected.toSet()).isEmpty()
            checks[key] = row.copy(
                totalStudents = row.totalStudents + 1,
                completeStudents = row.completeStudents + if (complete) 1 else 0,
                partialStudents = row.partialStudents + if (!complete && !missing) 1 else 0,
                missingStudents = row.missingStudents + if (missing) 1 else 0,
            )
        }
        return PreFinalCheckSummaryDto(checks.values.toList())
    }

    @Transactional
    fun advanceToPreFinal(current: CurrentUser, examIdRaw: String): TraditionalExamDto {
        requireCoordinator(current)
        val exam = exam(current, examIdRaw)
        // Idempotent for the offline outbox: re-sending an applied transition is a no-op.
        if (TraditionalExamStatus.entries.indexOf(exam.status) >= TraditionalExamStatus.entries.indexOf(TraditionalExamStatus.PRE_FINAL)) {
            return examDto(exam)
        }
        if (exam.status != TraditionalExamStatus.CONFIRMED) throw conflict("Exam must be CONFIRMED to advance to PRE_FINAL")
        val rows = confirmationRepository.findAllByExamId(exam.id)
        if (rows.isEmpty() || rows.any { it.confirmedAt == null }) throw conflict("All teachers must confirm before PRE_FINAL")
        exam.status = TraditionalExamStatus.PRE_FINAL
        examRepository.save(exam)
        return examDto(exam)
    }

    @Transactional
    fun finalize(current: CurrentUser, examIdRaw: String, coordinatorId: String?, remarks: String?): TraditionalExamDto {
        requireCoordinator(current)
        val exam = exam(current, examIdRaw)
        // Idempotent for the offline outbox: re-sending an applied finalize is a no-op.
        if (TraditionalExamStatus.entries.indexOf(exam.status) >= TraditionalExamStatus.entries.indexOf(TraditionalExamStatus.FINALIZED)) {
            return examDto(exam)
        }
        if (exam.status != TraditionalExamStatus.CONFIRMED && exam.status != TraditionalExamStatus.PRE_FINAL) {
            throw conflict("Exam must be CONFIRMED or PRE_FINAL to finalize")
        }
        val rows = confirmationRepository.findAllByExamId(exam.id)
        if (rows.isNotEmpty() && rows.any { it.confirmedAt == null }) throw conflict("Teachers are still pending confirmation")
        val coordinator = coordinatorId?.let { parseUuidOrNull(it) } ?: current.userId
        exam.status = TraditionalExamStatus.FINALIZED
        exam.finalizedAt = clock.instant()
        exam.finalizedBy = coordinator
        exam.coordinatorRemarks = remarks
        examRepository.save(exam)
        return examDto(exam)
    }

    @Transactional
    fun publish(current: CurrentUser, examIdRaw: String, coordinatorId: String?): TraditionalExamDto {
        requireCoordinator(current)
        val exam = exam(current, examIdRaw)
        if (exam.status == TraditionalExamStatus.PUBLISHED) return examDto(exam)
        if (exam.status != TraditionalExamStatus.FINALIZED) throw conflict("Exam must be FINALIZED before publishing")
        val coordinator = coordinatorId?.let { parseUuidOrNull(it) } ?: current.userId
        exam.status = TraditionalExamStatus.PUBLISHED
        exam.publishedAt = clock.instant()
        exam.publishedBy = coordinator
        examRepository.save(exam)
        fanOutResults(exam)
        return examDto(exam)
    }

    @Transactional
    fun remindTeacher(current: CurrentUser, examIdRaw: String, teacherIdRaw: String) {
        requireStaffWriter(current)
        val exam = exam(current, examIdRaw)
        val teacher = userRepository.findById(parseUuid(teacherIdRaw, "teacher id")).orElse(null)
            ?: throw notFound("Teacher not found")
        notify(
            userId = teacher.id,
            title = "Mark entry reminder",
            message = "Please enter and confirm the marks for " + exam.title + ".",
            route = "teacher_exams",
            label = "Open exam",
            urgency = "HIGH",
            priority = "HIGH",
        )
    }

    // ---------------------------------------------------------------- subjects / students

    @Transactional(readOnly = true)
    fun examSubjects(current: CurrentUser, examIdRaw: String): List<SubjectConfigDto> {
        val exam = exam(current, examIdRaw)
        if (!visibleTo(current, exam)) throw notFound("Exam not found")
        return subjectRepository.findAllByExamIdOrderByOrderIndexAsc(exam.id).map(::subjectDto)
    }

    @Transactional(readOnly = true)
    fun students(current: CurrentUser, examIdRaw: String, grade: String?): List<StudentDto> {
        requireStaffWriter(current)
        val exam = exam(current, examIdRaw)
        val target = grade?.takeIf { it.isNotBlank() } ?: exam.gradeLevel
        return studentsForExam(exam).filter { it.gradeLevel == null || it.gradeLevel == target }.map(::studentDto)
    }

    @Transactional(readOnly = true)
    fun marks(current: CurrentUser, examIdRaw: String): List<TraditionalMarkDto> {
        requireStaffWriter(current)
        val exam = exam(current, examIdRaw)
        return markRepository.findAllByExamId(exam.id).map { markDto(it, publicExamId(exam)) }
    }

    @Transactional
    fun saveMarks(current: CurrentUser, examIdRaw: String, entries: List<MarkEntryDto>): List<TraditionalMarkDto> {
        requireStaffWriter(current)
        val exam = exam(current, examIdRaw)
        // Published results are immutable for learners and parents; the client
        // locks editing too (UI-H4), so reject any late or out-of-order write.
        if (exam.status == TraditionalExamStatus.PUBLISHED) throw conflict("Published exam marks are immutable")
        if (entries.isEmpty()) return emptyList()
        val operator = user(current)
        val subjects = subjectRepository.findAllByExamIdOrderByOrderIndexAsc(exam.id).associateBy { it.subjectId }
        // A confirmed-but-unfinalized exam is still editable; a fresh write demotes it
        // back to IN_PROGRESS and invalidates the confirmation gate (client TE-7).
        val editable = exam.status == TraditionalExamStatus.PENDING ||
            exam.status == TraditionalExamStatus.IN_PROGRESS ||
            exam.status == TraditionalExamStatus.CONFIRMED
        val saved = mutableListOf<TraditionalMarkEntity>()
        for (entry in entries) {
            val subject = subjects[entry.subjectId] ?: throw invalidArgument("Unknown subject " + entry.subjectId)
            if (!editable && !hasEditPermission(exam.id, parseUuid(entry.studentId, "student id"), operator.id)) {
                throw conflict("Exam marks are locked; an approved edit permission is required")
            }
            if (entry.rawScore < 0 || entry.rawScore > subject.maxScore) {
                throw invalidArgument("Score for " + entry.subjectId + " must be between 0 and " + subject.maxScore)
            }
            val components = subjectComponentDtos(subject)
            if (subject.subjectType == TraditionalSubjectType.COMBINED && components.isNotEmpty()) {
                val scores = entry.componentScores ?: throw invalidArgument("Combined subject " + subject.subjectId + " requires component scores")
                var sum = 0
                for (component in components) {
                    val value = scores[component.componentId] ?: 0
                    if (value < 0 || value > component.maxScore) {
                        throw invalidArgument("Component " + component.name + " must be between 0 and " + component.maxScore)
                    }
                    sum += value
                }
                if (sum != entry.rawScore) throw invalidArgument("Component scores must sum to " + entry.rawScore)
            }
            val studentId = parseUuid(entry.studentId, "student id")
            val row = markRepository.findByExamIdAndStudentIdAndSubjectId(exam.id, studentId, entry.subjectId)
                ?: TraditionalMarkEntity().apply {
                    this.examId = exam.id
                    this.studentId = studentId
                    this.subjectId = entry.subjectId
                }
            val percentage = percentage(entry.rawScore, subject.maxScore)
            row.rawScore = entry.rawScore
            row.percentage = percentage
            row.gradeBand = gradeBand(percentage, gradingFor(exam.gradeLevel, exam.schoolId))
            row.componentScores = entry.componentScores?.let { mapper.writeValueAsString(it) }
            row.enteredBy = operator.id
            row.enteredAt = clock.instant()
            row.confirmedByTeacher = false
            row.confirmedAt = null
            markRepository.save(row)
            saved += row
        }
        if (exam.status == TraditionalExamStatus.PENDING || exam.status == TraditionalExamStatus.CONFIRMED) {
            if (exam.status == TraditionalExamStatus.CONFIRMED) {
                val confirmations = confirmationRepository.findAllByExamId(exam.id)
                confirmations.forEach { it.confirmedAt = null }
                confirmationRepository.saveAll(confirmations)
            }
            exam.status = TraditionalExamStatus.IN_PROGRESS
            examRepository.save(exam)
        }
        if (!editable) consumeEditPermission(exam.id, saved.map { it.studentId }.toSet(), operator.id)
        return saved.map { markDto(it, publicExamId(exam)) }
    }

    @Transactional
    fun confirmMarks(current: CurrentUser, examIdRaw: String, grade: String?) {
        requireStaffWriter(current)
        val exam = exam(current, examIdRaw)
        // A confirm replayed after publication is a no-op, not an error: the client
        // outbox retries lifecycle steps and must not get stuck on an applied one.
        if (exam.status == TraditionalExamStatus.PUBLISHED) return
        val target = grade?.takeIf { it.isNotBlank() } ?: exam.gradeLevel
        val now = clock.instant()
        val rows = confirmationRepository.findAllByExamId(exam.id)
        val existing = rows.firstOrNull { it.teacherId == current.userId && it.gradeLevel == target }
            ?: TraditionalConfirmationEntity().apply {
                this.examId = exam.id
                this.teacherId = current.userId
                this.gradeLevel = target
            }
        existing.confirmedAt = now
        confirmationRepository.save(existing)
        val students = studentsForExam(exam).filter { it.gradeLevel == null || it.gradeLevel == target }
        val studentIds = students.map { it.id }.toSet()
        if (studentIds.isNotEmpty()) {
            val gradeMarks = markRepository.findAllByExamIdAndStudentIdIn(exam.id, studentIds)
            gradeMarks.forEach {
                it.confirmedByTeacher = true
                it.confirmedAt = now
            }
            markRepository.saveAll(gradeMarks)
        }
        val all = confirmationRepository.findAllByExamId(exam.id)
        if (exam.status == TraditionalExamStatus.IN_PROGRESS && all.isNotEmpty() && all.all { it.confirmedAt != null }) {
            exam.status = TraditionalExamStatus.CONFIRMED
            examRepository.save(exam)
        }
    }

    // ---------------------------------------------------------------- reports

    @Transactional(readOnly = true)
    fun myResult(current: CurrentUser, examIdRaw: String, studentIdRaw: String?): TraditionalStudentReportDto {
        val exam = exam(current, examIdRaw)
        val student = resolveReportStudent(current, studentIdRaw)
        if (exam.status != TraditionalExamStatus.PUBLISHED) {
            throw ApiException(ApiErrorCode.FORBIDDEN, "Results are not published yet")
        }
        val marks = markRepository.findAllByExamIdAndStudentId(exam.id, student.id)
        if (marks.isEmpty()) throw notFound("No marks recorded for this student")
        return report(current, exam, student, marks)
    }

    @Transactional(readOnly = true)
    fun analytics(current: CurrentUser, examIdRaw: String): TraditionalExamAnalyticsDto {
        requireStaffReader(current)
        val exam = exam(current, examIdRaw)
        val marks = markRepository.findAllByExamId(exam.id)
        if (marks.isEmpty()) throw notFound("No marks recorded for this exam")
        val subjects = subjectRepository.findAllByExamIdOrderByOrderIndexAsc(exam.id).associateBy { it.subjectId }
        val grading = gradingFor(exam.gradeLevel, exam.schoolId)

        val byStudent = marks.groupBy { it.studentId }
        val names = userRepository.findAllById(byStudent.keys).associate { it.id to it.name }
        val totals = byStudent.mapValues { (_, rows) -> rows.sumOf { it.rawScore } }
        // The client's grade table, offline analytics and offline report all use the
        // sum of a student's subject percentages as the comparable metric, so the
        // server must use the same scale or the online and cached offline views
        // disagree (traditional_exams_audit.md section 7).
        val studentPercentages = byStudent.mapValues { (_, rows) ->
            rows.sumOf { it.percentage ?: percentage(it.rawScore, subjects[it.subjectId]?.maxScore ?: exam.maxScore) }
        }
        val percentages = studentPercentages.values.toList()
        val classMean = if (percentages.isNotEmpty()) percentages.average() else 0.0
        val sorted = percentages.sorted()
        val median = if (sorted.isEmpty()) 0.0 else if (sorted.size % 2 == 0) {
            (sorted[sorted.size / 2] + sorted[sorted.size / 2 - 1]) / 2.0
        } else {
            sorted[sorted.size / 2]
        }
        val variance = if (percentages.size > 1) percentages.sumOf { (it - classMean) * (it - classMean) } / (percentages.size - 1) else 0.0
        val ranked = studentPercentages.entries.sortedByDescending { it.value }
            .mapIndexed { index, entry -> Ranked(entry.key, totals[entry.key] ?: 0, entry.value, index + 1) }

        val top = ranked.take(5).map {
            TopPerformerDto(it.studentId.toString(), names[it.studentId] ?: "Unknown Student", it.totalScore, it.percentage, it.rank)
        }
        val struggling = ranked.filter { it.percentage < 50.0 }.take(5).map { rank ->
            val weak = marks.filter { it.studentId == rank.studentId && (it.percentage ?: 0.0) < 50.0 }
                .map { subjects[it.subjectId]?.name ?: it.subjectId }
            StrugglingStudentDto(rank.studentId.toString(), names[rank.studentId] ?: "Unknown Student", rank.totalScore, rank.percentage, weak)
        }
        val subjectAverages = marks.groupBy { it.subjectId }.mapValues { (_, rows) -> rows.mapNotNull { it.percentage }.average() }
        val gradeDistribution = marks.mapNotNull { it.gradeBand }.groupingBy { it }.eachCount()
        val classComparison = classComparison(exam, byStudent.keys, studentPercentages)
        val tally = confirmationTally(exam.id)
        return TraditionalExamAnalyticsDto(
            examId = publicExamId(exam),
            examTitle = exam.title,
            gradeLevel = exam.gradeLevel,
            classMean = round2(classMean),
            medianScore = round2(median),
            standardDeviation = round2(sqrt(variance)),
            highestScore = percentages.maxOrNull()?.toInt() ?: 0,
            lowestScore = percentages.minOrNull()?.toInt() ?: 0,
            topPerformers = top,
            strugglingStudents = struggling,
            gradeDistribution = gradeDistribution,
            subjectAverages = subjectAverages.mapValues { round2(it.value) },
            classComparison = classComparison.ifEmpty { null },
            totalStudents = totals.size,
            confirmedTeachers = tally.confirmed,
            totalTeachers = tally.total,
        )
    }

    @Transactional(readOnly = true)
    fun gradeWideRanking(current: CurrentUser, examIdRaw: String): List<StudentGradeRowDto> {
        requireStaffReader(current)
        return gradeTable(exam(current, examIdRaw))
    }

    @Transactional(readOnly = true)
    fun perClassRankings(current: CurrentUser, examIdRaw: String): Map<String, List<StudentGradeRowDto>> {
        requireStaffReader(current)
        return gradeTable(exam(current, examIdRaw)).groupBy { it.classTag }.toSortedMap()
    }

    @Transactional(readOnly = true)
    fun gradeAnalysis(current: CurrentUser, examIdRaw: String): TraditionalGradeAnalysisDto {
        requireStaffReader(current)
        val exam = exam(current, examIdRaw)
        val subjects = subjectRepository.findAllByExamIdOrderByOrderIndexAsc(exam.id)
        val table = gradeTable(exam)
        if (table.isEmpty()) throw notFound("No marks recorded for this exam")
        val complete = table.filter { it.subjects.size >= subjects.size && it.subjects.none { s -> s.rawScore == 0 || s.components?.any { c -> c.rawScore == 0 } == true } }
        if (complete.isEmpty()) throw notFound("No complete marks recorded for this exam")
        val columns = meanColumns(subjects)
        val summaries = complete.groupBy { it.classTag }.map { (classTag, rows) ->
            val subjectAverages = columns.mapNotNull { (key, subjectName, componentName) ->
                rawMean(rows, subjectName, componentName)?.let { key to it }
            }.toMap()
            ClassGradeAnalysisDto(
                classTag = classTag,
                teacherName = teacherNameForClass(classTag, exam.schoolId),
                subjectAverages = subjectAverages,
                subjectPositions = emptyMap(),
                totalAverage = round2(rows.map { it.totalPercentage }.average()),
                overallPosition = 0,
            )
        }
        val keys = summaries.flatMap { it.subjectAverages.keys }.distinct()
        val positions = keys.associateWith { key ->
            summaries.filter { it.subjectAverages.containsKey(key) }
                .sortedByDescending { it.subjectAverages[key] ?: 0.0 }
                .mapIndexed { index, row -> row.classTag to index + 1 }.toMap()
        }
        val overall = summaries.sortedByDescending { it.totalAverage }.mapIndexed { index, row -> row.classTag to index + 1 }.toMap()
        val finalized = summaries.map {
            it.copy(
                subjectPositions = it.subjectAverages.keys.associateWith { key -> positions[key]?.get(it.classTag) ?: 0 },
                overallPosition = overall[it.classTag] ?: 0,
            )
        }.sortedBy { it.overallPosition }
        return TraditionalGradeAnalysisDto(publicExamId(exam), exam.gradeLevel, exam.term.displayName(), exam.year, finalized)
    }

    @Transactional(readOnly = true)
    fun teacherClass(current: CurrentUser, teacherIdRaw: String): String? {
        requireStaffReader(current)
        val teacherId = parseUuid(teacherIdRaw, "teacher id")
        return teacherClassTag(teacherId)
    }

    // ---------------------------------------------------------------- edit requests

    @Transactional(readOnly = true)
    fun editRequests(current: CurrentUser, examIdRaw: String): List<EditRequestDto> {
        requireStaffReader(current)
        val exam = exam(current, examIdRaw)
        return editRequestRepository.findAllByExamIdOrderByCreatedAtDesc(exam.id).map { editRequestDto(it, publicExamId(exam)) }
    }

    @Transactional
    fun requestEdit(current: CurrentUser, examIdRaw: String, request: EditRequestDto): EditRequestDto {
        requireStaffWriter(current)
        val exam = exam(current, examIdRaw)
        if (request.reason.isBlank()) throw invalidArgument("An edit reason is required")
        // The client generates the request id locally and later approves/denies by it
        // (the response is best-effort); adopt it so those calls resolve, and replay
        // of the same request is idempotent.
        val requestedId = parseUuidOrNull(request.id)
        if (requestedId != null) {
            editRequestRepository.findById(requestedId).orElse(null)?.let { existing ->
                return editRequestDto(existing, publicExamId(existing.examId))
            }
        }
        val row = TraditionalEditRequestEntity().apply {
            if (requestedId != null) id = requestedId
            this.examId = exam.id
            requesterId = current.userId
            studentId = parseUuid(request.studentId, "student id")
            subjectId = request.subjectId
            oldScore = request.oldScore
            newScore = request.newScore
            reason = request.reason.trim()
            status = TraditionalEditStatus.PENDING
        }
        editRequestRepository.save(row)
        return editRequestDto(row, publicExamId(row.examId))
    }

    @Transactional
    fun approveEditRequest(current: CurrentUser, requestIdRaw: String, coordinatorId: String?): EditRequestDto {
        requireCoordinator(current)
        val row = editRequest(requestIdRaw)
        if (row.requesterId == current.userId) throw ApiException(ApiErrorCode.FORBIDDEN, "A coordinator cannot approve their own request")
        row.status = TraditionalEditStatus.APPROVED
        row.reviewedBy = coordinatorId?.let { parseUuidOrNull(it) } ?: current.userId
        row.reviewedAt = clock.instant()
        editRequestRepository.save(row)
        grantPermission(current, row.examId, row.studentId, row.requesterId)
        notify(row.requesterId, "Edit request approved", "Your edit request for " + row.subjectId + " was approved.", "teacher_exams", "View exam", "HIGH", "HIGH")
        return editRequestDto(row, publicExamId(row.examId))
    }

    @Transactional
    fun denyEditRequest(current: CurrentUser, requestIdRaw: String, coordinatorId: String?, reason: String?): EditRequestDto {
        requireCoordinator(current)
        val row = editRequest(requestIdRaw)
        row.status = TraditionalEditStatus.DENIED
        row.reviewedBy = coordinatorId?.let { parseUuidOrNull(it) } ?: current.userId
        row.reviewedAt = clock.instant()
        row.coordinatorComment = reason
        editRequestRepository.save(row)
        notify(row.requesterId, "Edit request denied", "Your edit request was denied" + (reason?.let { ": " + it } ?: "."), "teacher_exams", "View exam", "HIGH", "HIGH")
        return editRequestDto(row, publicExamId(row.examId))
    }

    @Transactional(readOnly = true)
    fun checkEditPermission(current: CurrentUser, examIdRaw: String, studentIdRaw: String, teacherIdRaw: String): Boolean {
        val exam = exam(current, examIdRaw)
        return hasEditPermission(exam.id, parseUuid(studentIdRaw, "student id"), parseUuid(teacherIdRaw, "teacher id"))
    }

    @Transactional
    fun grantEditPermission(current: CurrentUser, examIdRaw: String, permission: EditPermissionDto): Unit {
        requireCoordinator(current)
        val exam = exam(current, examIdRaw)
        val studentId = parseUuid(permission.studentId, "student id")
        val teacherId = parseUuid(permission.teacherId, "teacher id")
        markPermissionUsed(exam.id, studentId, teacherId)
        grantPermission(current, exam.id, studentId, teacherId)
    }

    @Transactional
    fun consumeEditPermission(current: CurrentUser, examIdRaw: String, studentIdRaw: String, teacherIdRaw: String) {
        requireStaffWriter(current)
        val exam = exam(current, examIdRaw)
        markPermissionUsed(exam.id, parseUuid(studentIdRaw, "student id"), parseUuid(teacherIdRaw, "teacher id"))
    }

    // ---------------------------------------------------------------- grade config

    @Transactional(readOnly = true)
    fun gradeSubjectConfig(current: CurrentUser, gradeLevel: String, schoolIdRaw: String?): List<SubjectConfigDto>? {
        requireStaffReader(current)
        val schoolId = schoolId(current, schoolIdRaw)
        val rows = if (schoolId != null) {
            subjectConfigRepository.findAllBySchoolIdAndGradeLevelOrderByOrderIndexAsc(schoolId, gradeLevel)
        } else {
            subjectConfigRepository.findAllByGradeLevelOrderByOrderIndexAsc(gradeLevel)
        }
        if (rows.isEmpty()) return null
        return rows.map { subjectConfigDto(it) }
    }

    @Transactional
    fun saveGradeSubjectConfig(current: CurrentUser, gradeLevel: String, schoolIdRaw: String?, subjects: List<SubjectConfigDto>) {
        requireCoordinator(current)
        validateSubjects(subjects)
        val schoolId = schoolId(current, schoolIdRaw)
        subjectConfigRepository.deleteAllByScope(schoolId, gradeLevel)
        subjects.forEachIndexed { index, dto ->
            subjectConfigRepository.save(TraditionalSubjectConfigEntity().apply {
                this.schoolId = schoolId
                this.gradeLevel = gradeLevel
                subjectId = dto.subjectId
                name = dto.name
                maxScore = dto.maxScore
                subjectType = dto.type
                isOptional = dto.isOptional
                components = dto.components?.let { mapper.writeValueAsString(it) }
                orderIndex = index
            })
        }
    }

    @Transactional(readOnly = true)
    fun gradingConfig(current: CurrentUser, gradeLevel: String, schoolIdRaw: String?): GradingConfigDto {
        requireStaffReader(current)
        return gradingFor(gradeLevel, schoolId(current, schoolIdRaw))
    }

    @Transactional
    fun saveGradingConfig(current: CurrentUser, gradeLevel: String, schoolIdRaw: String?, config: GradingConfigDto) {
        requireCoordinator(current)
        val schoolId = schoolId(current, schoolIdRaw)
        val rows = if (schoolId != null) {
            gradingConfigRepository.findAllBySchoolIdAndGradeLevel(schoolId, gradeLevel)
        } else {
            gradingConfigRepository.findAllByGradeLevel(gradeLevel)
        }
        val row = rows.firstOrNull() ?: TraditionalGradingConfigEntity().apply {
            this.schoolId = schoolId
            this.gradeLevel = gradeLevel
        }
        row.bands = mapper.writeValueAsString(config.bands)
        row.overallBands = config.overallBands?.let { mapper.writeValueAsString(it) }
        gradingConfigRepository.save(row)
    }

    // ================================================================ internals

    private data class Ranked(val studentId: UUID, val totalScore: Int, val percentage: Double, val rank: Int)

    private data class ConfirmationTally(val total: Int, val confirmed: Int, val pending: List<String>)

    /**
     * "Teachers of record" for an exam: everyone who entered marks plus everyone
     * who has a confirmation row. Counting confirmation rows alone reports the
     * first confirmer as the whole teacher set, which would let one teacher close
     * an exam; the client gates auto-promotion on totalTeachers == confirmedCount,
     * so the server must count the teachers who actually have marks at stake.
     */
    private fun confirmationTally(examId: UUID): ConfirmationTally {
        val confirmations = confirmationRepository.findAllByExamId(examId)
        val confirmed = confirmations.filter { it.confirmedAt != null }.map { it.teacherId }.toSet()
        val pending = confirmations.filter { it.confirmedAt == null }.map { it.teacherId }.toSet()
        val authors = markRepository.findAllByExamId(examId).mapNotNull { it.enteredBy }.toSet()
        val all = authors + confirmed + pending
        return ConfirmationTally(
            total = all.size,
            confirmed = all.count { it in confirmed },
            pending = (all - confirmed).map { it.toString() }.sorted(),
        )
    }

    /**
     * Resolves an exam by the caller's id space — a legacy server UUID or the
     * Android client's deterministic id (TRAD_Grade4_OPENER_2026_T1) — and
     * enforces school visibility.
     */
    private fun exam(current: CurrentUser, examIdRaw: String): TraditionalExamEntity {
        val entity = resolveExam(current, examIdRaw)
        if (!visibleTo(current, entity)) throw notFound("Exam not found")
        return entity
    }

    private fun resolveExam(current: CurrentUser, examIdRaw: String): TraditionalExamEntity {
        val raw = examIdRaw.trim()
        parseUuidOrNull(raw)?.let { uuid ->
            return examRepository.findById(uuid).orElse(null) ?: throw notFound("Exam not found")
        }
        val matches = examRepository.findAllByClientExamId(raw)
        if (matches.isEmpty()) throw notFound("Exam not found")
        if (matches.size == 1) return matches.first()
        // The deterministic client id omits the school, so fall back to the caller's tenant.
        val schoolId = scopeSchoolId(current)
        return matches.firstOrNull { it.schoolId == schoolId } ?: throw notFound("Exam not found")
    }

    /** The id every endpoint and payload speaks: the client id when present, else the UUID. */
    private fun publicExamId(exam: TraditionalExamEntity): String = exam.clientExamId ?: exam.id.toString()

    private fun publicExamId(examId: UUID): String =
        examRepository.findById(examId).orElse(null)?.let { publicExamId(it) } ?: examId.toString()

    private fun editRequest(requestIdRaw: String): TraditionalEditRequestEntity {
        val id = parseUuid(requestIdRaw, "edit request id")
        return editRequestRepository.findById(id).orElse(null) ?: throw notFound("Edit request not found")
    }

    private fun user(current: CurrentUser): UserEntity =
        userRepository.findById(current.userId).orElse(null) ?: throw notFound("User not found")

    private fun primaryChild(current: CurrentUser): UserEntity? =
        userRepository.findByParentUserId(current.userId).firstOrNull()

    /**
     * School-scoped visibility. Client-assigned exam ids are guessable
     * (TRAD_...), so a teacher must not be able to read another school's exam
     * by id; only a platform admin crosses tenants. Legacy rows with no school
     * stay visible.
     */
    private fun visibleTo(current: CurrentUser, exam: TraditionalExamEntity): Boolean {
        if (current.role == Role.ADMIN) return true
        return exam.schoolId == null || exam.schoolId == scopeSchoolId(current)
    }

    /** The caller's tenant; a parent inherits the linked child's school. */
    private fun scopeSchoolId(current: CurrentUser): UUID? {
        val own = runCatching { user(current).schoolId }.getOrNull()
        if (own != null) return own
        if (current.role != Role.PARENT) return null
        return primaryChild(current)?.schoolId
    }

    private fun publishedForSchool(schoolId: UUID?, grade: String?): List<TraditionalExamEntity> {
        val published = examRepository.findAllByStatusOrderByPublishedAtDesc(TraditionalExamStatus.PUBLISHED)
        return published.filter { exam ->
            (exam.schoolId == null || exam.schoolId == schoolId) &&
                (grade.isNullOrBlank() || exam.gradeLevel.equals(grade, ignoreCase = true))
        }
    }

    private fun examsForStaff(user: UserEntity): List<TraditionalExamEntity> {
        val schoolScoped = if (user.schoolId != null) {
            examRepository.findAllBySchoolIdOrderByCreatedAtDesc(user.schoolId!!)
        } else {
            examRepository.findAll().sortedByDescending { it.createdAt }
        }
        if (user.role != Role.TEACHER || isCoordinator(user)) return schoolScoped
        val grades = teacherClassRepository.findAllByTeacherUserIdAndIsActiveTrueOrderByNameAsc(user.id)
            .map { it.gradeLevel }.toSet()
        if (grades.isEmpty()) return schoolScoped
        return schoolScoped.filter { it.gradeLevel in grades }
    }

    private fun isCoordinator(user: UserEntity): Boolean =
        user.subRole == SubRole.GRADE_COORDINATOR || user.subRole == SubRole.ICT_ADMIN

    private fun resolveReportStudent(current: CurrentUser, studentIdRaw: String?): UserEntity {
        val requested = studentIdRaw?.takeIf { it.isNotBlank() }?.let { parseUuid(it, "student id") }
        return when (current.role) {
            Role.STUDENT -> {
                val self = user(current)
                if (requested != null && requested != self.id) {
                    throw ApiException(ApiErrorCode.FORBIDDEN, "Cannot read another student's results")
                }
                self
            }
            Role.PARENT -> {
                val childId = requested ?: primaryChild(current)?.id
                    ?: throw notFound("No linked child")
                val child = userRepository.findById(childId).orElse(null) ?: throw notFound("Child not found")
                if (child.parentUserId != current.userId) {
                    throw ApiException(ApiErrorCode.FORBIDDEN, "Not your linked child")
                }
                child
            }
            else -> {
                val id = requested ?: throw invalidArgument("studentId is required")
                userRepository.findById(id).orElse(null) ?: throw notFound("Student not found")
            }
        }
    }

    private fun report(current: CurrentUser, exam: TraditionalExamEntity, student: UserEntity, marks: List<TraditionalMarkEntity>): TraditionalStudentReportDto {
        val subjects = subjectRepository.findAllByExamIdOrderByOrderIndexAsc(exam.id).associateBy { it.subjectId }
        val grading = gradingFor(exam.gradeLevel, exam.schoolId)
        val subjectResults = marks.sortedBy { it.subjectId }.mapNotNull { mark ->
            val subject = subjects[mark.subjectId] ?: return@mapNotNull null
            subjectResult(subject, mark, grading)
        }
        val totalScore = subjectResults.sumOf { it.rawScore }
        val overallPercentage = if (subjectResults.isNotEmpty()) subjectResults.map { it.percentage }.average() else 0.0
        val classTag = classTagOf(student, exam)
        val cohort = gradeTable(exam).filter { it.classTag == classTag }
        val ordered = cohort.sortedByDescending { it.totalPercentage }
        val position = ordered.indexOfFirst { it.studentId == student.id.toString() }.let { if (it < 0) 0 else it + 1 }
        val school = exam.schoolId?.let { schoolRepository.findById(it).orElse(null) } ?: student.schoolId?.let { schoolRepository.findById(it).orElse(null) }
        return TraditionalStudentReportDto(
            studentId = student.id.toString(),
            studentName = student.name,
            admissionNumber = student.studentAdmissionNumber.orEmpty(),
            gradeLevel = exam.gradeLevel,
            classTag = classTag,
            term = exam.term.displayName(),
            year = exam.year,
            subjectResults = subjectResults,
            totalScore = totalScore,
            overallPercentage = round2(overallPercentage),
            // Mirror the client's offline report fallback (models/traditional/
            // TraditionalExamGrading.kt: overallBand(totalScore)), so an online report
            // and a cached offline report never disagree on the overall grade.
            overallGrade = overallBand(totalScore, grading),
            classPosition = position,
            totalStudentsInClass = ordered.size,
            teacherRemarks = exam.coordinatorRemarks,
            schoolName = school?.name.orEmpty(),
            schoolLogo = school?.logoUrl,
        )
    }

    private fun subjectResult(subject: TraditionalExamSubjectEntity, mark: TraditionalMarkEntity, grading: GradingConfigDto): TraditionalSubjectResultDto {
        val percentage = mark.percentage ?: percentage(mark.rawScore, subject.maxScore)
        val components = subjectComponentDtos(subject)
        val componentScores = parseComponentScores(mark.componentScores)
        val componentResults = if (subject.subjectType == TraditionalSubjectType.COMBINED && components.isNotEmpty()) {
            components.map { component ->
                val score = componentScores[component.componentId] ?: 0
                val pct = percentage(score, component.maxScore)
                SubjectComponentResultDto(component.name, score, component.maxScore, round2(pct), gradeBand(pct, grading))
            }
        } else {
            null
        }
        val details = componentScores.entries.joinToString(", ") { (id, score) ->
            (components.find { it.componentId == id }?.name ?: id) + ": " + score
        }.ifBlank { null }
        return TraditionalSubjectResultDto(
            subjectName = subject.name,
            rawScore = mark.rawScore,
            maxScore = subject.maxScore,
            percentage = round2(percentage),
            grade = mark.gradeBand ?: gradeBand(percentage, grading),
            isCombined = subject.subjectType == TraditionalSubjectType.COMBINED,
            componentDetails = details,
            components = componentResults,
        )
    }

    private fun gradeTable(exam: TraditionalExamEntity): List<StudentGradeRowDto> {
        val subjects = subjectRepository.findAllByExamIdOrderByOrderIndexAsc(exam.id).associateBy { it.subjectId }
        val grading = gradingFor(exam.gradeLevel, exam.schoolId)
        val marks = markRepository.findAllByExamId(exam.id)
        if (marks.isEmpty()) return emptyList()
        val students = userRepository.findAllById(marks.map { it.studentId }.distinct()).associateBy { it.id }
        val rows = marks.groupBy { it.studentId }.mapNotNull { (studentId, studentMarks) ->
            val student = students[studentId] ?: return@mapNotNull null
            val results = studentMarks.mapNotNull { mark ->
                subjects[mark.subjectId]?.let { subjectResult(it, mark, grading) }
            }
            val totalScore = results.sumOf { it.rawScore }
            StudentGradeRowDto(
                rank = 0,
                studentId = studentId.toString(),
                studentName = student.name,
                classTag = classTagOf(student, exam),
                subjects = results,
                totalPercentage = round2(results.sumOf { it.percentage }),
                totalScore = totalScore,
                overallGrade = overallBand(totalScore, grading),
            )
        }
        return rows.sortedByDescending { it.totalPercentage }.mapIndexed { index, row -> row.copy(rank = index + 1) }
    }

    private fun classComparison(exam: TraditionalExamEntity, studentIds: Set<UUID>, percentages: Map<UUID, Double>): Map<String, Double> {
        val students = userRepository.findAllById(studentIds).associateBy { it.id }
        return studentIds.groupBy { classTagOf(students[it] ?: return@groupBy exam.gradeLevel, exam) }
            .mapValues { (_, ids) -> round2(ids.mapNotNull { percentages[it] }.average()) }
    }

    private fun classTagOf(student: UserEntity, exam: TraditionalExamEntity): String {
        val membership = classMembershipRepository.findAllByStudentId(student.id).firstOrNull()
        if (membership != null) {
            teacherClassRepository.findById(membership.classId).orElse(null)?.let { return it.name }
        }
        return student.gradeLevel ?: exam.gradeLevel
    }

    private fun teacherNameForClass(classTag: String, schoolId: UUID?): String {
        val teachers = if (schoolId != null) userRepository.findAllBySchoolIdAndGradeLevelAndRole(schoolId, classTag, Role.TEACHER) else emptyList()
        return teachers.firstOrNull()?.name.orEmpty()
    }

    private fun teacherClassTag(teacherId: UUID): String? =
        teacherClassRepository.findAllByTeacherUserIdAndIsActiveTrueOrderByNameAsc(teacherId).firstOrNull()?.name

    private fun meanColumns(subjects: List<TraditionalExamSubjectEntity>): List<Triple<String, String, String?>> =
        subjects.flatMap { subject ->
            val components = subjectComponentDtos(subject)
            if (subject.subjectType == TraditionalSubjectType.COMBINED && components.size == 2) {
                components.map { Triple(it.name, subject.name, it.name) }
            } else {
                listOf(Triple(subject.name, subject.name, null))
            }
        }

    private fun rawMean(rows: List<StudentGradeRowDto>, subjectName: String, componentName: String?): Double? {
        val values = rows.mapNotNull { row ->
            val result = row.subjects.find { it.subjectName == subjectName } ?: return@mapNotNull null
            if (componentName != null) {
                result.components?.find { it.name == componentName }?.rawScore?.toDouble()
            } else {
                result.rawScore.toDouble()
            }
        }
        return values.takeIf { it.isNotEmpty() }?.average()?.let { round2(it) }
    }

    private fun studentsForExam(exam: TraditionalExamEntity): List<UserEntity> {
        val schoolId = exam.schoolId
        val grade = exam.gradeLevel
        val scoped = if (schoolId != null) {
            userRepository.findAllBySchoolIdAndGradeLevelAndRole(schoolId, grade, Role.STUDENT)
        } else {
            userRepository.findAllByGradeLevelAndRole(grade, Role.STUDENT)
        }
        if (scoped.isNotEmpty()) return scoped.filter { it.isActive }
        val marks = markRepository.findAllByExamId(exam.id).map { it.studentId }.distinct()
        return userRepository.findAllById(marks).filter { it.isActive }
    }

    // ---------------------------------------------------------------- grading

    private fun gradingFor(gradeLevel: String, schoolId: UUID?): GradingConfigDto =
        gradingConfigService.configFor(gradeLevel, schoolId)

    private fun percentage(raw: Int, max: Int): Double =
        if (max > 0) (raw.toDouble() / max * 100.0).coerceIn(0.0, 100.0) else 0.0

    private fun gradeBand(percentage: Double, config: GradingConfigDto): String =
        gradingConfigService.band(percentage, config)

    private fun overallBand(totalScore: Int, config: GradingConfigDto): String =
        gradingConfigService.overallBand(totalScore, config)

    // ---------------------------------------------------------------- payload mapping

    private fun examDto(exam: TraditionalExamEntity): TraditionalExamDto = TraditionalExamDto(
        examId = publicExamId(exam),
        title = exam.title,
        term = exam.term,
        gradeLevel = exam.gradeLevel,
        year = exam.year,
        status = exam.status,
        subjects = subjectRepository.findAllByExamIdOrderByOrderIndexAsc(exam.id).map(::subjectDto),
        maxScore = exam.maxScore,
        autoGenerated = exam.autoGenerated,
        createdAt = exam.createdAt.toEpochMilli(),
        finalizedAt = exam.finalizedAt?.toEpochMilli(),
        finalizedBy = exam.finalizedBy?.toString(),
        coordinatorRemarks = exam.coordinatorRemarks,
        publishedAt = exam.publishedAt?.toEpochMilli(),
        publishedBy = exam.publishedBy?.toString(),
        schoolId = exam.schoolId?.toString(),
    )

    private fun subjectDto(entity: TraditionalExamSubjectEntity): SubjectConfigDto = SubjectConfigDto(
        subjectId = entity.subjectId,
        name = entity.name,
        maxScore = entity.maxScore,
        type = entity.subjectType,
        components = subjectComponentDtos(entity),
        isOptional = entity.isOptional,
    )

    private fun subjectConfigDto(entity: TraditionalSubjectConfigEntity): SubjectConfigDto = SubjectConfigDto(
        subjectId = entity.subjectId,
        name = entity.name,
        maxScore = entity.maxScore,
        type = entity.subjectType,
        components = parseComponents(entity.components),
        isOptional = entity.isOptional,
    )

    private fun subjectComponentDtos(entity: TraditionalExamSubjectEntity): List<SubjectComponentDto> =
        parseComponents(entity.components)

    /**
     * [examPublicId] is the id the client knows; the mark id mirrors the client's
     * deterministic "<examId>_<studentId>_<subjectId>" key so a save response
     * replaces the local row instead of inserting a duplicate under the UUID.
     */
    private fun markDto(entity: TraditionalMarkEntity, examPublicId: String): TraditionalMarkDto = TraditionalMarkDto(
        id = examPublicId + "_" + entity.studentId + "_" + entity.subjectId,
        examId = examPublicId,
        studentId = entity.studentId.toString(),
        subjectId = entity.subjectId,
        rawScore = entity.rawScore,
        percentage = entity.percentage?.let { round2(it) },
        gradeBand = entity.gradeBand,
        componentScores = parseComponentScores(entity.componentScores),
        confirmedByTeacher = entity.confirmedByTeacher,
        confirmedAt = entity.confirmedAt?.toEpochMilli(),
        enteredAt = entity.enteredAt.toEpochMilli(),
    )

    private fun studentDto(entity: UserEntity): StudentDto = StudentDto(
        id = entity.id.toString(),
        name = entity.name,
        className = entity.gradeLevel.orEmpty(),
        admissionNumber = entity.studentAdmissionNumber.orEmpty(),
    )

    private fun editRequestDto(entity: TraditionalEditRequestEntity, examPublicId: String): EditRequestDto = EditRequestDto(
        id = entity.id.toString(),
        examId = examPublicId,
        teacherId = entity.requesterId.toString(),
        studentId = entity.studentId.toString(),
        subjectId = entity.subjectId,
        oldScore = entity.oldScore,
        newScore = entity.newScore,
        reason = entity.reason,
        status = entity.status,
        reviewedBy = entity.reviewedBy?.toString(),
        reviewedAt = entity.reviewedAt?.toEpochMilli(),
        coordinatorComment = entity.coordinatorComment,
        createdAt = entity.createdAt.toEpochMilli(),
    )

    // ---------------------------------------------------------------- persistence helpers

    private fun saveSubjects(examId: UUID, subjects: List<SubjectConfigDto>) {
        subjects.forEachIndexed { index, dto ->
            subjectRepository.save(TraditionalExamSubjectEntity().apply {
                this.examId = examId
                subjectId = dto.subjectId
                name = dto.name
                maxScore = dto.maxScore
                subjectType = dto.type
                isOptional = dto.isOptional
                components = dto.components?.let { mapper.writeValueAsString(it) }
                orderIndex = index
            })
        }
    }

    private fun grantPermission(current: CurrentUser, examId: UUID, studentId: UUID, teacherId: UUID) {
        editPermissionRepository.save(TraditionalEditPermissionEntity().apply {
            this.examId = examId
            this.studentId = studentId
            this.teacherId = teacherId
            grantedBy = current.userId
            grantedAt = clock.instant()
            expiresAt = clock.instant().plus(Duration.ofHours(24))
            used = false
        })
    }

    private fun hasEditPermission(examId: UUID, studentId: UUID, teacherId: UUID): Boolean {
        val now = clock.instant()
        return editPermissionRepository.findAllByExamIdAndStudentIdAndTeacherIdOrderByGrantedAtDesc(examId, studentId, teacherId)
            .any { !it.used && it.expiresAt.isAfter(now) }
    }

    private fun consumeEditPermission(examId: UUID, studentIds: Set<UUID>, teacherId: UUID) {
        val now = clock.instant()
        val rows = studentIds.flatMap {
            editPermissionRepository.findAllByExamIdAndStudentIdAndTeacherIdOrderByGrantedAtDesc(examId, it, teacherId)
        }.filter { !it.used && it.expiresAt.isAfter(now) }
        rows.forEach { it.used = true }
        editPermissionRepository.saveAll(rows)
    }

    private fun markPermissionUsed(examId: UUID, studentId: UUID, teacherId: UUID) {
        val now = clock.instant()
        editPermissionRepository.findAllByExamIdAndStudentIdAndTeacherIdOrderByGrantedAtDesc(examId, studentId, teacherId)
            .filter { !it.used && it.expiresAt.isAfter(now) }
            .forEach { it.used = true }
        editPermissionRepository.flush()
    }

    // ---------------------------------------------------------------- auth helpers

    private fun requireStaffWriter(current: CurrentUser) {
        if (current.role != Role.TEACHER && current.role != Role.ADMIN) {
            throw ApiException(ApiErrorCode.FORBIDDEN, "Staff access required")
        }
    }

    private fun requireStaffReader(current: CurrentUser) {
        if (current.role != Role.TEACHER && current.role != Role.ADMIN) {
            throw ApiException(ApiErrorCode.FORBIDDEN, "Staff access required")
        }
    }

    private fun requireCoordinator(current: CurrentUser) {
        if (current.role == Role.ADMIN) return
        if (current.role == Role.TEACHER && (current.subRole == SubRole.GRADE_COORDINATOR || current.subRole == SubRole.ICT_ADMIN)) return
        throw ApiException(ApiErrorCode.FORBIDDEN, "Coordinator access required")
    }

    private fun notify(
        userId: UUID,
        title: String,
        message: String,
        route: String?,
        label: String?,
        urgency: String,
        priority: String,
    ) {
        notificationService.notifyUser(
            userId = userId,
            title = title,
            message = message,
            type = NotificationType.EXAM,
            urgency = NotificationUrgency.valueOf(urgency),
            priority = NotificationPriority.valueOf(priority),
            actionRoute = route,
            actionLabel = label,
        )
    }

    private fun fanOutResults(exam: TraditionalExamEntity) {
        val studentIds = markRepository.findAllByExamId(exam.id).map { it.studentId }.distinct()
        val students = userRepository.findAllById(studentIds)
        for (student in students) {
            notify(student.id, "Results Ready: " + exam.title, "Your " + exam.title + " results are ready. Tap to view and download your report.", "exam_results/" + exam.id, "View Results", "HIGH", "HIGH")
        }
        for (student in students) {
            val parentId = student.parentUserId ?: continue
            notify(parentId, "Results Ready: " + student.name + " - " + exam.title, "Your child's " + exam.title + " results are ready. Tap to view the report.", "student_report/" + student.id, "View Report", "HIGH", "HIGH")
        }
    }

    private fun schoolId(current: CurrentUser, raw: String?): UUID? {
        raw?.takeIf { it.isNotBlank() }?.let { return parseUuidOrNull(it) }
        return user(current).schoolId
    }

    // ---------------------------------------------------------------- json helpers

    private fun parseComponents(json: String?): List<SubjectComponentDto> {
        if (json.isNullOrBlank()) return emptyList()
        val node = runCatching { mapper.readTree(json) }.getOrNull() ?: return emptyList()
        if (!node.isArray) return emptyList()
        return (0 until node.size()).mapNotNull { i ->
            val item = node.get(i)
            val id = item.get("componentId")?.asString() ?: return@mapNotNull null
            SubjectComponentDto(
                componentId = id,
                name = item.get("name")?.asString() ?: id,
                maxScore = item.get("maxScore")?.intValue() ?: 0,
                weight = item.get("weight")?.doubleValue() ?: 1.0,
            )
        }
    }

    private fun parseComponentScores(json: String?): Map<String, Int> {
        if (json.isNullOrBlank()) return emptyMap()
        val node = runCatching { mapper.readTree(json) }.getOrNull() ?: return emptyMap()
        if (!node.isObject) return emptyMap()
        return node.properties().associate { it.key to (it.value.intValue()) }
    }

    private fun validateSubjects(subjects: List<SubjectConfigDto>) {
        subjects.forEach { subject ->
            if (subject.maxScore <= 0) throw invalidArgument("Subject " + subject.name + " needs a positive maxScore")
            if (subject.type == TraditionalSubjectType.COMBINED && subject.components.isNullOrEmpty()) {
                throw invalidArgument("Combined subject " + subject.name + " needs components")
            }
        }
    }

    private fun ensureTransitionAllowed(exam: TraditionalExamEntity, target: TraditionalExamStatus) {
        val order = TraditionalExamStatus.entries
        if (order.indexOf(target) < order.indexOf(exam.status)) throw conflict("Cannot move exam backwards to " + target)
    }

    private fun enumOr(values: List<TraditionalExamStatus>, raw: String, label: String): TraditionalExamStatus =
        values.firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) }
            ?: throw invalidArgument("Unknown " + label + ": " + raw)

    private fun parseUuid(raw: String, label: String): UUID =
        runCatching { UUID.fromString(raw) }.getOrNull() ?: throw invalidArgument(label + " is not a valid identifier")

    private fun parseUuidOrNull(raw: String): UUID? = runCatching { UUID.fromString(raw) }.getOrNull()

    private fun round2(value: Double): Double = (value * 100).roundToInt() / 100.0
}
