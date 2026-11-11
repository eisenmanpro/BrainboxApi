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
import com.afrithecus.brainbox.api.traditional.model.TraditionalOmitPolicy
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
import com.afrithecus.brainbox.api.traditional.web.CaptureStudentDto
import com.afrithecus.brainbox.api.traditional.web.CaptureStudentStatus
import com.afrithecus.brainbox.api.traditional.web.CaptureSummaryDto
import com.afrithecus.brainbox.api.traditional.web.ClassCaptureSummaryDto
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
import com.afrithecus.brainbox.api.traditional.web.TraditionalClassDto
import com.afrithecus.brainbox.api.traditional.web.TraditionalSubjectResultDto
import com.afrithecus.brainbox.api.traditional.web.UpdateTraditionalExamRequest
import com.afrithecus.brainbox.api.traditional.web.ValidateAccessRequest
import com.afrithecus.brainbox.api.traditional.web.ValidateAccessResponse
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
        return exams.sortedByDescending { it.createdAt }.map { examDto(it, current) }
    }

    @Transactional(readOnly = true)
    fun getExam(current: CurrentUser, examIdRaw: String): TraditionalExamDto {
        val exam = exam(current, examIdRaw)
        if (!visibleTo(current, exam)) throw notFound("Exam not found")
        if ((current.role == Role.STUDENT || current.role == Role.PARENT) && exam.status != TraditionalExamStatus.PUBLISHED) {
            throw ApiException(ApiErrorCode.FORBIDDEN, "Results are not published yet")
        }
        return examDto(exam, current)
    }

    @Transactional
    fun createExam(current: CurrentUser, request: CreateTraditionalExamRequest): TraditionalExamDto {
        // A capture session is a coordinator's scheduling decision. Class teachers
        // receive it and enter marks; they do not create sessions for their grade.
        requireCoordinator(current)
        validateSubjects(request.subjects)
        val user = user(current)
        // CREATE_EXAM replays from the client outbox: storing the client id makes
        // the call idempotent and keeps the exam addressable under the id the
        // device stores locally.
        val clientExamId = request.examId?.trim()?.takeIf { it.isNotBlank() && parseUuidOrNull(it) == null }
        if (clientExamId != null) {
            examRepository.findBySchoolIdAndClientExamId(user.schoolId, clientExamId)?.let { existing ->
                // An outbox replay may follow a first attempt whose broadcast failed. The
                // exam row is already idempotent; retry the one side effect it has not recorded.
                if (existing.broadcastAt == null) {
                    broadcastSession(existing, user)
                    existing.broadcastAt = clock.instant()
                    examRepository.save(existing)
                }
                return examDto(existing, current)
            }
        }
        val exam = TraditionalExamEntity().apply {
            title = request.title.trim()
            term = request.term
            gradeLevel = request.gradeLevel.trim()
            year = request.year
            status = TraditionalExamStatus.PENDING
            maxScore = request.maxScore
            autoGenerated = request.autoGenerated
            omitPolicy = request.omitPolicy
            sessionRemarks = (request.remarks ?: request.sessionRemarks)?.trim()?.takeIf { it.isNotBlank() }
                ?: ("Capture scores for " + request.title.trim() + ".")
            schoolId = user.schoolId
            createdBy = user.id
            this.clientExamId = clientExamId
        }
        examRepository.save(exam)
        saveSubjects(exam.id, request.subjects)
        broadcastSession(exam, user)
        exam.broadcastAt = clock.instant()
        examRepository.save(exam)
        return examDto(exam, current)
    }

    /** Coordinator re-edits a saved capture session before it is finalized. */
    @Transactional
    fun updateExam(
        current: CurrentUser,
        examIdRaw: String,
        request: UpdateTraditionalExamRequest,
    ): TraditionalExamDto {
        requireCoordinator(current)
        val exam = exam(current, examIdRaw)
        if (exam.status == TraditionalExamStatus.FINALIZED || exam.status == TraditionalExamStatus.PUBLISHED) {
            throw conflict("A finalized exam can no longer be edited")
        }
        request.title?.trim()?.takeIf { it.isNotBlank() }?.let { exam.title = it }
        request.remarks?.let {
            exam.sessionRemarks = it.trim().takeIf { remark -> remark.isNotBlank() }
                ?: ("Capture scores for " + exam.title + ".")
        }
        request.omitPolicy?.let { exam.omitPolicy = it }
        examRepository.save(exam)
        return examDto(exam, current)
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
                examDto(existing, current)
            } else {
                val created = TraditionalExamEntity().apply {
                    title = label + " " + termNumber + " " + year + " - " + grade
                    this.term = term
                    gradeLevel = grade
                    this.year = year
                    status = TraditionalExamStatus.PENDING
                    autoGenerated = true
                    sessionRemarks = "Capture scores for " + title + "."
                    schoolId = user.schoolId
                    createdBy = user.id
                    this.clientExamId = clientExamId
                }
                examRepository.save(created)
                saveSubjects(created.id, subjects)
                examDto(created, current)
            }
        }
    }

    @Transactional
    fun updateStatus(current: CurrentUser, examIdRaw: String, statusRaw: String): TraditionalExamDto {
        requireCoordinator(current)
        val exam = exam(current, examIdRaw)
        val target = enumOr(TraditionalExamStatus.entries, statusRaw, "exam status")
        // The lifecycle moves one guarded step at a time (spec 10 section 1.1). Terminal
        // steps route through their own method so the confirmation/finalization
        // preconditions cannot be skipped by a single status write, and every replay of
        // an already-applied step is an idempotent no-op for the offline outbox.
        return when (target) {
            TraditionalExamStatus.PUBLISHED -> publish(current, examIdRaw, current.userId.toString())
            TraditionalExamStatus.FINALIZED -> finalize(current, examIdRaw, current.userId.toString(), null)
            TraditionalExamStatus.PRE_FINAL -> advanceToPreFinal(current, examIdRaw)
            TraditionalExamStatus.CONFIRMED -> {
                if (orderAtLeast(exam, TraditionalExamStatus.CONFIRMED)) return examDto(exam, current)
                if (exam.status != TraditionalExamStatus.IN_PROGRESS) {
                    throw conflict("Exam must be IN_PROGRESS before it can be CONFIRMED")
                }
                val tally = confirmationTally(exam.id)
                if (tally.total == 0 || tally.confirmed != tally.total) {
                    throw conflict("All teachers must confirm before the exam is CONFIRMED")
                }
                exam.status = target
                examRepository.save(exam)
                examDto(exam, current)
            }
            TraditionalExamStatus.IN_PROGRESS -> {
                if (exam.status == TraditionalExamStatus.IN_PROGRESS) return examDto(exam, current)
                if (exam.status != TraditionalExamStatus.PENDING) {
                    throw conflict("Only a PENDING exam can be opened for marking")
                }
                exam.status = target
                examRepository.save(exam)
                examDto(exam, current)
            }
            TraditionalExamStatus.PENDING -> throw conflict("Cannot move exam backwards to PENDING")
        }
    }

    @Transactional(readOnly = true)
    fun confirmationStatus(current: CurrentUser, examIdRaw: String): ExamConfirmationStatusDto {
        requireStaffWriter(current)
        val exam = exam(current, examIdRaw)
        val tally = confirmationTally(exam.id)
        val names = userRepository.findAllById(tally.pending.mapNotNull { parseUuidOrNull(it) })
            .associate { it.id.toString() to it.name }
        return ExamConfirmationStatusDto(
            examId = publicExamId(exam),
            totalTeachers = tally.total,
            confirmedCount = tally.confirmed,
            pendingTeachers = tally.pending,
            pendingTeacherNames = tally.pending.map { names[it] ?: it },
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

    /**
     * The capture summary a class teacher sees after submitting and the coordinator
     * sees before finalizing. Deliberately returns identities and counts, not every
     * learner's marks (the brief's "client does not query all scores" rule).
     */
    @Transactional(readOnly = true)
    fun captureSummary(current: CurrentUser, examIdRaw: String): CaptureSummaryDto {
        requireStaffReader(current)
        val exam = exam(current, examIdRaw)
        val expectedSubjects = subjectRepository.findAllByExamIdOrderByOrderIndexAsc(exam.id).map { it.subjectId }
        val marksByStudent = markRepository.findAllByExamId(exam.id).groupBy { it.studentId }
        val students = studentsForExam(exam)
        val classes = students.groupBy { classTagOf(it, exam) }.toSortedMap().map { (classTag, classStudents) ->
            val rows = classStudents.map { student ->
                val recorded = marksByStudent[student.id].orEmpty()
                    .map { it.subjectId }
                    .filter { it in expectedSubjects }
                    .distinct()
                val status = when {
                    expectedSubjects.isNotEmpty() && recorded.size >= expectedSubjects.size -> CaptureStudentStatus.COMPLETE
                    recorded.isEmpty() -> CaptureStudentStatus.NONE
                    else -> CaptureStudentStatus.PARTIAL
                }
                CaptureStudentDto(
                    studentId = student.id.toString(),
                    name = student.name,
                    admissionNumber = student.studentAdmissionNumber.orEmpty(),
                    classTag = classTag,
                    status = status,
                    recordedSubjects = recorded.size,
                    expectedSubjects = expectedSubjects.size,
                )
            }
            ClassCaptureSummaryDto(
                classTag = classTag,
                teacherName = teacherNameForClass(classTag, exam.schoolId),
                totalStudents = rows.size,
                completeStudents = rows.count { it.status == CaptureStudentStatus.COMPLETE },
                partialStudents = rows.count { it.status == CaptureStudentStatus.PARTIAL },
                missingStudents = rows.count { it.status == CaptureStudentStatus.NONE },
                students = rows,
            )
        }
        val complete = classes.sumOf { it.completeStudents }
        val partial = classes.sumOf { it.partialStudents }
        val missing = classes.sumOf { it.missingStudents }
        val myConfirmed = confirmationRepository.findAllByExamId(exam.id)
            .any { it.teacherId == current.userId && it.confirmedAt != null }
        return CaptureSummaryDto(
            examId = publicExamId(exam),
            title = exam.title,
            gradeLevel = exam.gradeLevel,
            term = exam.term,
            year = exam.year,
            sessionRemarks = exam.sessionRemarks,
            totalStudents = complete + partial + missing,
            studentsWithMarks = complete + partial,
            studentsWithEmptyFields = partial,
            studentsWithNoScores = missing,
            myConfirmed = myConfirmed,
            classes = classes,
        )
    }

    @Transactional(readOnly = true)
    fun validateAccess(
        current: CurrentUser,
        examIdRaw: String,
        request: ValidateAccessRequest?,
    ): ValidateAccessResponse {
        requireStaffReader(current)
        exam(current, examIdRaw)
        val target = request?.grade?.takeIf { it.isNotBlank() }
        if (isOwnClass(current, target)) {
            return ValidateAccessResponse(
                allowed = false,
                unverified = false,
                reason = "A class teacher cannot capture marks for their own class",
            )
        }
        return ValidateAccessResponse(allowed = true)
    }

    @Transactional
    fun advanceToPreFinal(current: CurrentUser, examIdRaw: String): TraditionalExamDto {
        requireCoordinator(current)
        val exam = exam(current, examIdRaw)
        // Idempotent for the offline outbox: re-sending an applied transition is a no-op.
        if (TraditionalExamStatus.entries.indexOf(exam.status) >= TraditionalExamStatus.entries.indexOf(TraditionalExamStatus.PRE_FINAL)) {
            return examDto(exam, current)
        }
        if (exam.status != TraditionalExamStatus.CONFIRMED) throw conflict("Exam must be CONFIRMED to advance to PRE_FINAL")
        val rows = confirmationRepository.findAllByExamId(exam.id)
        if (rows.isEmpty() || rows.any { it.confirmedAt == null }) throw conflict("All teachers must confirm before PRE_FINAL")
        exam.status = TraditionalExamStatus.PRE_FINAL
        examRepository.save(exam)
        return examDto(exam, current)
    }

    @Transactional
    fun finalize(
        current: CurrentUser,
        examIdRaw: String,
        coordinatorId: String?,
        remarks: String?,
        omitPolicy: TraditionalOmitPolicy? = null,
    ): TraditionalExamDto {
        requireCoordinator(current)
        val exam = exam(current, examIdRaw)
        // Idempotent for the offline outbox: re-sending an applied finalize is a no-op.
        if (TraditionalExamStatus.entries.indexOf(exam.status) >= TraditionalExamStatus.entries.indexOf(TraditionalExamStatus.FINALIZED)) {
            return examDto(exam, current)
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
        if (omitPolicy != null) exam.omitPolicy = omitPolicy
        snapshotAnalytics(exam)
        examRepository.save(exam)
        notifyAnalyticsCompleted(exam, current)
        return examDto(exam, current)
    }

    @Transactional
    fun publish(current: CurrentUser, examIdRaw: String, coordinatorId: String?): TraditionalExamDto {
        requireCoordinator(current)
        val exam = exam(current, examIdRaw)
        if (exam.status == TraditionalExamStatus.PUBLISHED) return examDto(exam, current)
        if (exam.status != TraditionalExamStatus.FINALIZED) throw conflict("Exam must be FINALIZED before publishing")
        val coordinator = coordinatorId?.let { parseUuidOrNull(it) } ?: current.userId
        exam.status = TraditionalExamStatus.PUBLISHED
        exam.publishedAt = clock.instant()
        exam.publishedBy = coordinator
        examRepository.save(exam)
        fanOutResults(exam)
        notify(current.userId, "Results published", exam.title + " results are now visible to learners and parents.", examRoute(exam), "View exam", "NORMAL", "NORMAL")
        return examDto(exam, current)
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
            route = examRoute(exam),
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
        if (isOwnClass(current, target)) {
            throw ApiException(ApiErrorCode.FORBIDDEN, "A class teacher cannot capture marks for their own class")
        }
        return studentsInScope(exam, target).map(::studentDto)
    }

    /**
     * The classes a staff user may pick for capture. Every class in the exam's grade
     * is listed; the caller's own class is returned with allowed=false so the client
     * can show why it is not selectable instead of silently returning an empty roster.
     */
    @Transactional(readOnly = true)
    fun captureClasses(current: CurrentUser, examIdRaw: String): List<TraditionalClassDto> {
        requireStaffReader(current)
        val exam = exam(current, examIdRaw)
        val schoolId = exam.schoolId
        val classes = if (schoolId != null) {
            teacherClassRepository.findAllBySchoolIdAndIsActiveTrueOrderByNameAsc(schoolId)
                .filter { it.gradeLevel.equals(exam.gradeLevel, ignoreCase = true) }
        } else {
            emptyList()
        }
        if (classes.isEmpty()) {
            return listOf(
                TraditionalClassDto(
                    classId = "",
                    name = exam.gradeLevel,
                    gradeLevel = exam.gradeLevel,
                    teacherId = null,
                    teacherName = null,
                    allowed = true,
                )
            )
        }
        val teachers = userRepository.findAllById(classes.map { it.teacherUserId }.distinct())
            .associateBy { it.id }
        return classes.map { cls ->
            TraditionalClassDto(
                classId = cls.id.toString(),
                name = cls.name,
                gradeLevel = cls.gradeLevel,
                teacherId = cls.teacherUserId.toString(),
                teacherName = teachers[cls.teacherUserId]?.name,
                allowed = !isOwnClass(current, cls.name),
            )
        }.sortedByDescending { it.allowed }
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
        val coordinatorOperator = isCoordinator(operator) || current.role == Role.ADMIN
        val reviewStage = exam.status == TraditionalExamStatus.CONFIRMED ||
            exam.status == TraditionalExamStatus.PRE_FINAL
        val coordinatorCanReview = coordinatorOperator && reviewStage
        // Non-negotiable owner rule: a class teacher can never capture marks for
        // their own class. Other classes in the grade remain available to them.
        val ownClasses = if (operator.role == Role.TEACHER) ownClassTags(operator.id) else emptySet()
        val saved = mutableListOf<TraditionalMarkEntity>()
        for (entry in entries) {
            val subject = subjects[entry.subjectId] ?: throw invalidArgument("Unknown subject " + entry.subjectId)
            val studentId = parseUuid(entry.studentId, "student id")
            entry.classId?.takeIf { it.isNotBlank() }?.let { classIdRaw ->
                val classId = parseUuid(classIdRaw, "class id")
                val clazz = teacherClassRepository.findById(classId).orElse(null)
                    ?: throw invalidArgument("Unknown class " + classIdRaw)
                if (exam.schoolId != null && clazz.schoolId != null && clazz.schoolId != exam.schoolId) {
                    throw invalidArgument("That class is not in this exam's school")
                }
                if (!clazz.gradeLevel.equals(exam.gradeLevel, ignoreCase = true)) {
                    throw invalidArgument("That class is not in the exam's grade")
                }
                if (classMembershipRepository.findByClassIdAndStudentId(classId, studentId) == null) {
                    throw invalidArgument("The learner is not a member of that class")
                }
            }
            if (ownClasses.isNotEmpty()) {
                val student = userRepository.findById(studentId).orElse(null)
                if (student != null && ownClasses.any { it.equals(classTagOf(student, exam), ignoreCase = true) }) {
                    throw ApiException(ApiErrorCode.FORBIDDEN, "A class teacher cannot capture marks for their own class")
                }
            }
            // A coordinator may add a missing learner from the review panel while
            // the exam is still capture/locked-pre-final; published marks stay frozen.
            if (!editable && !coordinatorCanReview && !hasEditPermission(exam.id, studentId, operator.id)) {
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
        if (exam.analyticsSnapshot != null) exam.analyticsSnapshot = null
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
        if (reviewStage && coordinatorOperator) notifyCoordinatorReviewEdits(exam, saved, operator)
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
        val students = studentsInScope(exam, target)
        val studentIds = students.map { it.id }.toSet()
        if (studentIds.isNotEmpty()) {
            val gradeMarks = markRepository.findAllByExamIdAndStudentIdIn(exam.id, studentIds)
            gradeMarks.forEach {
                it.confirmedByTeacher = true
                it.confirmedAt = now
            }
            markRepository.saveAll(gradeMarks)
        }
        // Promote only once every teacher of record has confirmed, not merely every
        // existing confirmation row: a teacher who entered marks but has not confirmed
        // must keep the exam open (the client gates on totalTeachers == confirmedCount).
        val tally = confirmationTally(exam.id)
        if (exam.status == TraditionalExamStatus.IN_PROGRESS && tally.total > 0 && tally.confirmed == tally.total) {
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
        exam.analyticsSnapshot?.takeIf { it.isNotBlank() }?.let { snapshot ->
            return runCatching { mapper.readValue(snapshot, TraditionalExamAnalyticsDto::class.java) }
                .getOrElse { analyticsFor(exam) }
        }
        return analyticsFor(exam)
    }

    /** Compute the analytics exactly as the live read does; also used to capture the final snapshot. */
    private fun analyticsFor(exam: TraditionalExamEntity): TraditionalExamAnalyticsDto {
        if (markRepository.findAllByExamId(exam.id).isEmpty()) throw notFound("No marks recorded for this exam")
        val view = analysisView(exam)
        val marks = view.marks
        if (marks.isEmpty()) throw notFound("No complete marks remain after the selected omit policy")
        val subjects = view.subjects.associateBy { it.subjectId }
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
            omittedStudents = view.omittedStudents,
            excludedSubjects = view.excludedSubjects,
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
        val subjects = analysisView(exam).subjects
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
        requireEditRequester(current)
        val exam = exam(current, examIdRaw)
        // The client generates the request id locally and later approves/denies by it
        // (the response is best-effort); adopt it so those calls resolve, and replay
        // of the same request is idempotent. The replay lookup runs before the guards
        // so a retried request that already landed is echoed even if the exam moved on.
        val requestedId = parseUuidOrNull(request.id)
        if (requestedId != null) {
            editRequestRepository.findById(requestedId).orElse(null)?.let { existing ->
                return editRequestDto(existing, publicExamId(existing.examId))
            }
        }
        // Guards the client also enforces locally (spec 10 section 5.3 / Appendix A).
        // The server is the authority for an offline client that skipped them.
        // F7: a class teacher's "submitted" state opens the edit-request path before
        // the coordinator finalizes too. Published marks remain frozen.
        if (exam.status == TraditionalExamStatus.PUBLISHED) {
            throw conflict("Published exam marks are immutable")
        }
        if (exam.status == TraditionalExamStatus.PENDING || exam.status == TraditionalExamStatus.IN_PROGRESS) {
            throw conflict("Submit the marks before requesting an edit")
        }
        if (request.reason.isBlank()) throw invalidArgument("An edit reason is required")
        val subject = subjectRepository.findAllByExamIdOrderByOrderIndexAsc(exam.id)
            .firstOrNull { it.subjectId == request.subjectId }
            ?: throw invalidArgument("Unknown subject " + request.subjectId)
        if (request.newScore < 0 || request.newScore > subject.maxScore) {
            throw invalidArgument("The new score for " + request.subjectId + " must be between 0 and " + subject.maxScore)
        }
        val studentId = parseUuid(request.studentId, "student id")
        verifyEditRequesterTeaches(current, exam, studentId)
        val row = TraditionalEditRequestEntity().apply {
            if (requestedId != null) id = requestedId
            this.examId = exam.id
            requesterId = current.userId
            this.studentId = studentId
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
        // Offline replay: re-sending an applied approval is an idempotent no-op.
        if (row.status == TraditionalEditStatus.APPROVED) return editRequestDto(row, publicExamId(row.examId))
        if (row.status == TraditionalEditStatus.DENIED) throw conflict("This edit request was already denied")
        // F7 owner decision: a coordinator may approve their own request. The client
        // shows the responsibility warning before it calls this endpoint; the server
        // records the decision instead of hiding it behind an ICT-admin request.
        row.status = TraditionalEditStatus.APPROVED
        row.reviewedBy = coordinatorId?.let { parseUuidOrNull(it) } ?: current.userId
        row.reviewedAt = clock.instant()
        editRequestRepository.save(row)
        // Unlock the submitted exam: the requesting teacher re-enters the correction
        // and submits again, which re-runs the confirmation gate.
        val exam = examRepository.findById(row.examId).orElse(null)
        if (exam != null && exam.status != TraditionalExamStatus.PUBLISHED) {
            exam.status = TraditionalExamStatus.IN_PROGRESS
            // Reopening invalidates the finalize snapshot; the next finalize takes a fresh one.
            exam.analyticsSnapshot = null
            examRepository.save(exam)
            confirmationRepository.findAllByExamId(exam.id)
                .filter { it.teacherId == row.requesterId }
                .forEach { it.confirmedAt = null }
            confirmationRepository.flush()
        }
        grantPermission(current, row.examId, row.studentId, row.requesterId)
        notify(
            row.requesterId,
            "Edit request approved",
            "Your edit request for " + row.subjectId + " was approved. The exam is open again; correct the mark and submit it.",
            if (exam != null) examRoute(exam) else examRoute(row.examId),
            "Open exam",
            "HIGH",
            "HIGH",
        )
        return editRequestDto(row, publicExamId(row.examId))
    }

    @Transactional
    fun denyEditRequest(current: CurrentUser, requestIdRaw: String, coordinatorId: String?, reason: String?): EditRequestDto {
        requireCoordinator(current)
        val row = editRequest(requestIdRaw)
        // Offline replay: re-sending an applied denial is an idempotent no-op.
        if (row.status == TraditionalEditStatus.DENIED) return editRequestDto(row, publicExamId(row.examId))
        if (row.status == TraditionalEditStatus.APPROVED) throw conflict("This edit request was already approved")
        row.status = TraditionalEditStatus.DENIED
        row.reviewedBy = coordinatorId?.let { parseUuidOrNull(it) } ?: current.userId
        row.reviewedAt = clock.instant()
        row.coordinatorComment = reason
        editRequestRepository.save(row)
        notify(row.requesterId, "Edit request denied", "Your edit request was denied" + (reason?.let { ": " + it } ?: "."), examRoute(row.examId), "View exam", "HIGH", "HIGH")
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
        // F7 owner decision: every grade must open with the correct CBC learning
        // areas already populated. A school config wins; otherwise return the
        // researched grade-band defaults instead of null/empty.
        if (rows.isEmpty()) return TraditionalCurriculum.subjectsFor(gradeLevel)
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

    /**
     * The rows and columns that the finalized exam's analysis should use.
     *
     *  - The 10% rule: a subject column with marks for fewer than one-tenth of the
     *    grade's learners is treated as a subject the grade did not sit (or as a
     *    capture mistake), so it is excluded from the finalized analytics.
     *  - The coordinator's omit policy then decides which learners enter the analysis.
     *    REPLACE_ZEROS_WITH_ONE keeps the learner and substitutes 1 for a missing or
     *    zero subject instead of dropping them.
     *
     * Before finalization the raw capture is returned unchanged so a draft review
     * never hides work that is still being entered.
     */
    private fun analysisView(exam: TraditionalExamEntity): AnalysisView {
        val allSubjects = subjectRepository.findAllByExamIdOrderByOrderIndexAsc(exam.id)
        val rawMarks = markRepository.findAllByExamId(exam.id)
        val students = studentsForExam(exam)
        val finalized = exam.status == TraditionalExamStatus.FINALIZED || exam.status == TraditionalExamStatus.PUBLISHED
        if (!finalized) {
            return AnalysisView(
                subjects = allSubjects,
                marks = rawMarks,
                omittedStudents = 0,
                excludedSubjects = emptyList(),
            )
        }
        val excludedNames = mutableListOf<String>()
        val coveredSubjects = allSubjects.filter { subject ->
            val count = rawMarks.count { it.subjectId == subject.subjectId }
            val coverage = if (students.isEmpty()) 1.0 else count.toDouble() / students.size
            val keep = coverage >= 0.10
            if (!keep) excludedNames += subject.name
            keep
        }
        val effectiveSubjects = coveredSubjects.ifEmpty {
            allSubjects.filter { subject -> rawMarks.any { it.subjectId == subject.subjectId } }
                .ifEmpty { allSubjects }
        }
        val expected = effectiveSubjects.map { it.subjectId }.toSet()
        val rawByStudent = rawMarks.groupBy { it.studentId }
        val statuses = students.associate { student ->
            val recorded = rawByStudent[student.id].orEmpty().map { it.subjectId }.filter { it in expected }.toSet()
            val status = when {
                expected.isNotEmpty() && recorded.size >= expected.size -> CaptureStudentStatus.COMPLETE
                recorded.isEmpty() -> CaptureStudentStatus.NONE
                else -> CaptureStudentStatus.PARTIAL
            }
            student.id to status
        }
        val included = when (exam.omitPolicy) {
            TraditionalOmitPolicy.OMIT_NO_SCORES -> statuses.filterValues { it != CaptureStudentStatus.NONE }.keys
            TraditionalOmitPolicy.OMIT_INCOMPLETE -> statuses.filterValues { it != CaptureStudentStatus.PARTIAL }.keys
            TraditionalOmitPolicy.OMIT_ALL_INCOMPLETE -> statuses.filterValues { it == CaptureStudentStatus.COMPLETE }.keys
            TraditionalOmitPolicy.REPLACE_ZEROS_WITH_ONE -> statuses.keys
        }
        val adjusted = if (exam.omitPolicy == TraditionalOmitPolicy.REPLACE_ZEROS_WITH_ONE) {
            included.flatMap { studentId ->
                val bySubject = rawByStudent[studentId].orEmpty().associateBy { it.subjectId }
                effectiveSubjects.map { subject ->
                    val existing = bySubject[subject.subjectId]
                    when {
                        existing == null -> syntheticMark(exam, studentId, subject, 1)
                        existing.rawScore == 0 -> syntheticMark(exam, studentId, subject, 1)
                        else -> existing
                    }
                }
            }
        } else {
            rawMarks.filter { it.studentId in included && it.subjectId in expected }
        }
        return AnalysisView(
            subjects = effectiveSubjects,
            marks = adjusted,
            omittedStudents = (students.size - included.size).coerceAtLeast(0),
            excludedSubjects = excludedNames,
        )
    }

    private fun syntheticMark(
        exam: TraditionalExamEntity,
        studentId: UUID,
        subject: TraditionalExamSubjectEntity,
        score: Int,
    ): TraditionalMarkEntity = TraditionalMarkEntity().apply {
        this.examId = exam.id
        this.studentId = studentId
        this.subjectId = subject.subjectId
        rawScore = score
        val pct = percentage(score, subject.maxScore)
        percentage = pct
        gradeBand = gradeBand(pct, gradingFor(exam.gradeLevel, exam.schoolId))
        enteredAt = clock.instant()
    }

    private data class AnalysisView(
        val subjects: List<TraditionalExamSubjectEntity>,
        val marks: List<TraditionalMarkEntity>,
        val omittedStudents: Int,
        val excludedSubjects: List<String>,
    )

    private fun gradeTable(exam: TraditionalExamEntity): List<StudentGradeRowDto> {
        val view = analysisView(exam)
        val subjects = view.subjects.associateBy { it.subjectId }
        val grading = gradingFor(exam.gradeLevel, exam.schoolId)
        val marks = view.marks
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
        // A provisioned learner has no membership; the provisioned class link is
        // what tags it correctly in the per-class traditional tables.
        student.provisionedClassId?.let { provisioned ->
            teacherClassRepository.findById(provisioned).orElse(null)?.let { return it.name }
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

    /**
     * Learners a capture scope covers. [target] may be a grade ("Grade 4") or a
     * class name ("Grade 4A") — class teachers select their class, and before F7
     * the server compared that class name to gradeLevel and returned nobody.
     */
    private fun studentsInScope(exam: TraditionalExamEntity, target: String?): List<UserEntity> {
        val all = studentsForExam(exam)
        if (target.isNullOrBlank() || target.equals(exam.gradeLevel, ignoreCase = true)) return all
        val schoolId = exam.schoolId
        val classEntity = if (schoolId != null) {
            teacherClassRepository.findAllBySchoolIdAndIsActiveTrueOrderByNameAsc(schoolId)
                .firstOrNull { it.name.equals(target, ignoreCase = true) }
        } else {
            null
        }
        if (classEntity != null) {
            val memberIds = classMembershipRepository.findAllByClassId(classEntity.id)
                .map { it.studentId }
                .toSet()
            return all.filter { it.id in memberIds || it.provisionedClassId == classEntity.id }
        }
        return all.filter { it.gradeLevel.equals(target, ignoreCase = true) }
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

    private fun examDto(exam: TraditionalExamEntity, current: CurrentUser? = null): TraditionalExamDto = TraditionalExamDto(
        examId = publicExamId(exam),
        title = exam.title,
        term = exam.term,
        gradeLevel = exam.gradeLevel,
        year = exam.year,
        status = exam.status,
        subjects = subjectRepository.findAllByExamIdOrderByOrderIndexAsc(exam.id).map(::subjectDto),
        maxScore = exam.maxScore,
        autoGenerated = exam.autoGenerated,
        sessionRemarks = exam.sessionRemarks,
        omitPolicy = exam.omitPolicy,
        confirmedByMe = current != null && confirmationRepository.findAllByExamId(exam.id)
            .any { it.teacherId == current.userId && it.confirmedAt != null },
        submittedAt = current?.let { caller ->
            confirmationRepository.findAllByExamId(exam.id)
                .firstOrNull { it.teacherId == caller.userId && it.confirmedAt != null }
                ?.confirmedAt?.toEpochMilli()
        },
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

    /** True when [target] is the caller's own class; the owner rule forbids capturing it. */
    private fun isOwnClass(current: CurrentUser, target: String?): Boolean {
        if (current.role != Role.TEACHER || target.isNullOrBlank()) return false
        return ownClassTags(current.userId).any { it.equals(target, ignoreCase = true) }
    }

    private fun ownClassTags(teacherId: UUID): Set<String> =
        teacherClassRepository.findAllByTeacherUserIdAndIsActiveTrueOrderByNameAsc(teacherId)
            .map { it.name }
            .toSet()

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

    /**
     * Only C/TEACHER, GRADE_COORDINATOR and ICT_ADMIN may raise an edit request
     * (spec 10 section 5.3 guard 1 / Appendix A); a plain (sub-role-less) teacher may
     * not. A platform admin is accepted as the tenant-level override.
     */
    private fun requireEditRequester(current: CurrentUser) {
        if (current.role == Role.ADMIN) return
        if (current.role == Role.TEACHER &&
            (current.subRole == SubRole.CTEACHER || current.subRole == SubRole.GRADE_COORDINATOR || current.subRole == SubRole.ICT_ADMIN)
        ) return
        throw ApiException(ApiErrorCode.FORBIDDEN, "Only C/Teachers and coordinators can request a score edit")
    }

    /**
     * The requester must actually teach the affected student: a coordinator is exempt,
     * otherwise the teacher must have entered at least one mark in this exam or own an
     * active class matching the student's class (spec 10 Appendix A).
     */
    private fun verifyEditRequesterTeaches(current: CurrentUser, exam: TraditionalExamEntity, studentId: UUID) {
        if (current.role != Role.TEACHER) return
        if (current.subRole == SubRole.GRADE_COORDINATOR || current.subRole == SubRole.ICT_ADMIN) return
        if (markRepository.existsByExamIdAndEnteredBy(exam.id, current.userId)) return
        val student = userRepository.findById(studentId).orElse(null) ?: throw notFound("Student not found")
        val classTag = classTagOf(student, exam)
        if (teacherClassTag(current.userId) != classTag) {
            throw ApiException(ApiErrorCode.FORBIDDEN, "You do not teach this student's class")
        }
    }

    /** Persist the finalized analytics once, so a later read cannot drift from the published state. */
    private fun snapshotAnalytics(exam: TraditionalExamEntity) {
        exam.analyticsSnapshot = runCatching { mapper.writeValueAsString(analyticsFor(exam)) }.getOrNull()
    }

    /** Direct notification route for one capture session (F7-A5). */
    private fun examRoute(exam: TraditionalExamEntity): String = "exam_status/" + publicExamId(exam)

    private fun examRoute(examId: UUID): String = "exam_status/" + publicExamId(examId)

    /**
     * Tells the class teachers of the grade that a coordinator has opened a capture
     * session. This is the "broadcasted to all class teachers" half of the owner's
     * session-creation flow; without it the exam existed but nobody was told to capture it.
     */
    private fun broadcastSession(exam: TraditionalExamEntity, creator: UserEntity) {
        val schoolId = exam.schoolId ?: return
        val teachers = userRepository.findAllBySchoolIdAndIsActiveTrueOrderByNameAsc(schoolId)
            .filter { it.isActive && it.role == Role.TEACHER }
        val classTeacherIds = teacherClassRepository.findAllBySchoolIdAndIsActiveTrueOrderByNameAsc(schoolId)
            .filter { it.gradeLevel.equals(exam.gradeLevel, ignoreCase = true) }
            .map { it.teacherUserId }
            .toSet()
        val recipients = teachers.filter { teacher ->
            teacher.id != creator.id &&
                (teacher.id in classTeacherIds ||
                    teacher.gradeLevel.equals(exam.gradeLevel, ignoreCase = true) ||
                    teacher.subRole == SubRole.GRADE_COORDINATOR)
        }
        recipients.forEach { teacher ->
            notify(
                userId = teacher.id,
                title = "New paper exam session: " + exam.title,
                message = (exam.sessionRemarks ?: "Capture scores for " + exam.title + ".") +
                    " Open Paper Exams to enter and submit your class marks.",
                route = examRoute(exam),
                label = "Open paper exams",
                urgency = "HIGH",
                priority = "HIGH",
            )
        }
    }

    /** After finalize the coordinator and every school ICT admin get the analytics report signal. */
    private fun notifyAnalyticsCompleted(exam: TraditionalExamEntity, current: CurrentUser) {
        val schoolId = exam.schoolId
        val recipients = linkedSetOf(current.userId)
        if (schoolId != null) {
            userRepository.findAllBySchoolIdAndIsActiveTrueOrderByNameAsc(schoolId)
                .filter { it.isActive && it.subRole == SubRole.ICT_ADMIN }
                .forEach { recipients += it.id }
        }
        recipients.forEach { userId ->
            notify(
                userId = userId,
                title = "Analytics have been completed",
                message = exam.title + " was finalized. Review the analytics, download reports or publish the results.",
                route = examRoute(exam),
                label = "Open paper exams",
                urgency = "HIGH",
                priority = "HIGH",
            )
        }
    }

    /** The review panel's "edited by grade coordinator" responsibility message to the class teacher. */
    private fun notifyCoordinatorReviewEdits(
        exam: TraditionalExamEntity,
        saved: List<TraditionalMarkEntity>,
        coordinator: UserEntity,
    ) {
        val schoolId = exam.schoolId ?: return
        val classes = teacherClassRepository.findAllBySchoolIdAndIsActiveTrueOrderByNameAsc(schoolId)
            .associateBy { it.name.lowercase() }
        saved.map { it.studentId }.distinct().forEach { studentId ->
            val student = userRepository.findById(studentId).orElse(null) ?: return@forEach
            val classTag = classTagOf(student, exam)
            val classTeacher = classes[classTag.lowercase()]?.teacherUserId
                ?.let { userRepository.findById(it).orElse(null) } ?: return@forEach
            if (classTeacher.id == coordinator.id) return@forEach
            notify(
                userId = classTeacher.id,
                title = "Score edited by grade coordinator",
                message = "The scores for " + student.name + " were edited by the grade coordinator (" +
                    coordinator.name + "). Please review the exam.",
                route = examRoute(exam),
                label = "Open exam",
                urgency = "HIGH",
                priority = "HIGH",
            )
        }
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
        // Publish only the learners who survive the coordinator's finalize policy;
        // an omitted learner must not receive a report for marks left out of analysis.
        val studentIds = analysisView(exam).marks.map { it.studentId }.distinct()
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

    /** True when the exam has already reached [status] in the lifecycle order. */
    private fun orderAtLeast(exam: TraditionalExamEntity, status: TraditionalExamStatus): Boolean =
        TraditionalExamStatus.entries.indexOf(exam.status) >= TraditionalExamStatus.entries.indexOf(status)

    private fun enumOr(values: List<TraditionalExamStatus>, raw: String, label: String): TraditionalExamStatus =
        values.firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) }
            ?: throw invalidArgument("Unknown " + label + ": " + raw)

    private fun parseUuid(raw: String, label: String): UUID =
        runCatching { UUID.fromString(raw) }.getOrNull() ?: throw invalidArgument(label + " is not a valid identifier")

    private fun parseUuidOrNull(raw: String): UUID? = runCatching { UUID.fromString(raw) }.getOrNull()

    private fun round2(value: Double): Double = (value * 100).roundToInt() / 100.0
}
