package com.afrithecus.brainbox.api.gradebook

import com.afrithecus.brainbox.api.classes.entity.TeacherClassEntity
import com.afrithecus.brainbox.api.classes.repository.TeacherClassRepository
import com.afrithecus.brainbox.api.common.error.ApiErrorCode
import com.afrithecus.brainbox.api.common.error.ApiException
import com.afrithecus.brainbox.api.common.error.invalidArgument
import com.afrithecus.brainbox.api.common.error.notFound
import com.afrithecus.brainbox.api.gradebook.entity.GradebookAssessmentEntity
import com.afrithecus.brainbox.api.gradebook.entity.GradebookEntryEntity
import com.afrithecus.brainbox.api.gradebook.model.GradebookAssessmentType
import com.afrithecus.brainbox.api.gradebook.repository.GradebookAssessmentRepository
import com.afrithecus.brainbox.api.gradebook.repository.GradebookEntryRepository
import com.afrithecus.brainbox.api.gradebook.web.GradebookAssessmentPayload
import com.afrithecus.brainbox.api.gradebook.web.GradebookEntryPayload
import com.afrithecus.brainbox.api.gradebook.web.PublishedGradePayload
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.model.CurrentUser
import com.afrithecus.brainbox.api.identity.model.Role
import com.afrithecus.brainbox.api.identity.model.SubRole
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import com.afrithecus.brainbox.api.traditional.TraditionalGradingConfigService
import com.afrithecus.brainbox.api.traditional.model.ExamTerm
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * Teacher gradebook (docs/ongoing/api_gradebook_changes.md): manual assessments
 * and grades for a class. Exam/homework rows are aggregated client-side and are
 * not managed here. Client-supplied ids keep offline writes idempotent.
 */
@Service
class GradebookService(
    private val assessmentRepository: GradebookAssessmentRepository,
    private val entryRepository: GradebookEntryRepository,
    private val classRepository: TeacherClassRepository,
    private val userRepository: UserRepository,
    private val gradingConfigService: TraditionalGradingConfigService,
    private val clock: Clock,
) {

    // ---------------------------------------------------------------- assessments

    @Transactional(readOnly = true)
    fun assessments(current: CurrentUser, classIdRaw: String): List<GradebookAssessmentPayload> {
        val clazz = classForWrite(current, classIdRaw)
        return assessmentRepository.findAllByClassIdOrderByDateAssignedDesc(clazz.id).map(::assessmentPayload)
    }

    /** Idempotent upsert keyed on the client-supplied id. */
    @Transactional
    fun createAssessment(current: CurrentUser, classIdRaw: String, payload: GradebookAssessmentPayload): GradebookAssessmentPayload {
        val clazz = classForWrite(current, classIdRaw)
        val user = user(current)
        val type = assessmentType(payload.assessmentType)
        if (payload.title.isBlank()) throw invalidArgument("Assessment title is required")
        if (payload.maxScore <= 0) throw invalidArgument("Assessment maxScore must be positive")
        val clientId = payload.id.takeIf { it.isNotBlank() } ?: "assess_" + UUID.randomUUID()
        val row = assessmentRepository.findByClientId(clientId) ?: GradebookAssessmentEntity().apply {
            this.clientId = clientId
            this.classId = clazz.id
            this.createdBy = user.id
        }
        if (row.classId != clazz.id) throw notFound("Assessment not found")
        row.title = payload.title.trim()
        row.assessmentType = type.name
        row.maxScore = payload.maxScore
        row.dateAssigned = Instant.ofEpochMilli(payload.dateAssigned)
        row.cbcStrandTag = payload.cbcStrandTag
        row.term = payload.term
        row.isPublished = payload.isPublished
        row.countsTowardAverage = payload.countsTowardAverage
        row.schoolId = clazz.schoolId
        assessmentRepository.save(row)
        return assessmentPayload(row)
    }

    @Transactional
    fun updateAssessment(current: CurrentUser, classIdRaw: String, assessmentIdRaw: String, payload: GradebookAssessmentPayload): GradebookAssessmentPayload {
        val clazz = classForWrite(current, classIdRaw)
        val row = assessmentForClass(clazz, assessmentIdRaw)
        requireAssessmentOwner(current, row)
        if (payload.title.isBlank()) throw invalidArgument("Assessment title is required")
        if (payload.maxScore <= 0) throw invalidArgument("Assessment maxScore must be positive")
        row.title = payload.title.trim()
        row.assessmentType = assessmentType(payload.assessmentType).name
        row.maxScore = payload.maxScore
        row.dateAssigned = Instant.ofEpochMilli(payload.dateAssigned)
        row.cbcStrandTag = payload.cbcStrandTag
        row.term = payload.term
        row.isPublished = payload.isPublished
        row.countsTowardAverage = payload.countsTowardAverage
        assessmentRepository.save(row)
        return assessmentPayload(row)
    }

    /** Repeat-safe: a delete for an unknown assessment is a no-op. */
    @Transactional
    fun deleteAssessment(current: CurrentUser, classIdRaw: String, assessmentIdRaw: String) {
        val clazz = classForWrite(current, classIdRaw)
        val row = assessmentRepository.findByClientId(assessmentIdRaw) ?: return
        if (row.classId != clazz.id) return
        requireAssessmentOwner(current, row)
        entryRepository.deleteAllByAssessmentId(row.clientId)
        assessmentRepository.delete(row)
    }

    // ---------------------------------------------------------------- entries

    @Transactional(readOnly = true)
    fun gradebook(current: CurrentUser, classIdRaw: String): List<GradebookEntryPayload> {
        val clazz = classForWrite(current, classIdRaw)
        return entryRepository.findAllByClassId(clazz.id).map(::entryPayload)
    }

    @Transactional
    fun submitGrade(current: CurrentUser, payload: GradebookEntryPayload): GradebookEntryPayload {
        val clazz = classForWrite(current, payload.classId)
        return entryPayload(upsertEntry(current, clazz, payload))
    }

    @Transactional
    fun updateGrade(current: CurrentUser, entryIdRaw: String, payload: GradebookEntryPayload): GradebookEntryPayload {
        val existing = entryRepository.findByClientId(entryIdRaw)
            ?: throw notFound("Grade not found")
        val clazz = classForWrite(current, existing.classId.toString())
        val updated = upsertEntry(current, clazz, payload, existing)
        return entryPayload(updated)
    }

    @Transactional
    fun deleteGrade(current: CurrentUser, entryIdRaw: String) {
        val existing = entryRepository.findByClientId(entryIdRaw) ?: return
        classForWrite(current, existing.classId.toString())
        entryRepository.delete(existing)
    }

    @Transactional
    fun bulkGrades(current: CurrentUser, entries: List<GradebookEntryPayload>): List<GradebookEntryPayload> {
        if (entries.isEmpty()) return emptyList()
        return entries.map { payload ->
            val clazz = classForWrite(current, payload.classId)
            entryPayload(upsertEntry(current, clazz, payload))
        }
    }

    private fun upsertEntry(
        current: CurrentUser,
        clazz: TeacherClassEntity,
        payload: GradebookEntryPayload,
        existing: GradebookEntryEntity? = null,
    ): GradebookEntryEntity {
        val assessment = assessmentForClass(clazz, payload.assessmentId)
        val studentId = parseUuid(payload.studentId, "studentId")
        val student = userRepository.findById(studentId).orElse(null) ?: throw notFound("Student not found")
        val row = existing
            ?: entryRepository.findByClassIdAndAssessmentIdAndStudentId(clazz.id, assessment.clientId, studentId)
            ?: GradebookEntryEntity().apply {
                this.clientId = payload.id.takeIf { it.isNotBlank() } ?: "gb_" + UUID.randomUUID()
                this.classId = clazz.id
                this.assessmentId = assessment.clientId
                this.studentId = studentId
                this.teacherId = current.userId
            }
        val maxScore = assessment.maxScore
        val raw = payload.rawScore.coerceIn(0, maxScore.coerceAtLeast(0))
        row.studentName = student.name
        row.assessmentType = assessment.assessmentType
        row.assessmentTitle = assessment.title
        row.maxScore = maxScore
        row.rawScore = raw
        row.percentage = if (maxScore > 0) (raw * 100) / maxScore else 0
        row.cbcStrandTag = payload.cbcStrandTag ?: assessment.cbcStrandTag
        row.teacherNote = payload.teacherNote
        row.teacherId = current.userId
        row.gradedAt = if (payload.gradedAt > 0) Instant.ofEpochMilli(payload.gradedAt) else clock.instant()
        entryRepository.save(row)
        return row
    }

    // ---------------------------------------------------------------- published grades

    /** The signed-in learner's published grades (docs/ongoing/api_gradebook_changes.md section 9). */
    @Transactional(readOnly = true)
    fun studentGrades(current: CurrentUser, termRaw: String?): List<PublishedGradePayload> {
        if (current.role != Role.STUDENT) throw ApiException(ApiErrorCode.FORBIDDEN, "Student access only")
        return publishedFor(current.userId, termRaw)
    }

    /** A linked child's published grades; ownership enforced. */
    @Transactional(readOnly = true)
    fun childGrades(current: CurrentUser, childIdRaw: String, termRaw: String?): List<PublishedGradePayload> {
        val child = userRepository.findById(parseUuid(childIdRaw, "childId")).orElse(null)
            ?: throw notFound("Child not found")
        if (current.role != Role.ADMIN && child.parentUserId != current.userId) {
            throw ApiException(ApiErrorCode.FORBIDDEN, "Not your linked child")
        }
        return publishedFor(child.id, termRaw)
    }

    private fun publishedFor(studentId: UUID, termRaw: String?): List<PublishedGradePayload> {
        val entries = entryRepository.findAllByStudentId(studentId)
        if (entries.isEmpty()) return emptyList()
        val assessments = assessmentRepository.findAllByClientIdIn(entries.map { it.assessmentId }.toSet())
            .associateBy { it.clientId }
        val classes = classRepository.findAllById(assessments.values.map { it.classId }.distinct()).associateBy { it.id }
        val term = termRaw?.takeIf { it.isNotBlank() }?.let { raw ->
            ExamTerm.entries.firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) }
                ?: throw invalidArgument("Unknown term: " + raw)
        }
        return entries.mapNotNull { entry ->
            val assessment = assessments[entry.assessmentId] ?: return@mapNotNull null
            if (!assessment.isPublished) return@mapNotNull null
            if (term != null && assessment.term != term) return@mapNotNull null
            val clazz = classes[assessment.classId]
            val config = gradingConfigService.configFor(clazz?.gradeLevel ?: "", clazz?.schoolId)
            PublishedGradePayload(
                id = entry.clientId,
                assessmentId = assessment.clientId,
                assessmentTitle = assessment.title,
                assessmentType = assessment.assessmentType,
                term = assessment.term.name,
                score = entry.rawScore,
                maxScore = entry.maxScore,
                percentage = entry.percentage,
                gradeBand = gradingConfigService.band(entry.percentage.toDouble(), config),
                teacherNote = entry.teacherNote,
                gradedAt = entry.gradedAt.toEpochMilli(),
                countsTowardAverage = assessment.countsTowardAverage,
            )
        }.sortedByDescending { it.gradedAt }
    }

    // ---------------------------------------------------------------- payloads

    private fun assessmentPayload(row: GradebookAssessmentEntity) = GradebookAssessmentPayload(
        id = row.clientId,
        classId = row.classId.toString(),
        title = row.title,
        assessmentType = row.assessmentType,
        maxScore = row.maxScore,
        dateAssigned = row.dateAssigned.toEpochMilli(),
        cbcStrandTag = row.cbcStrandTag,
        term = row.term,
        isPublished = row.isPublished,
        countsTowardAverage = row.countsTowardAverage,
        createdBy = row.createdBy.toString(),
    )

    private fun entryPayload(row: GradebookEntryEntity) = GradebookEntryPayload(
        id = row.clientId,
        classId = row.classId.toString(),
        teacherId = row.teacherId.toString(),
        assessmentId = row.assessmentId,
        assessmentType = row.assessmentType,
        studentId = row.studentId.toString(),
        studentName = row.studentName,
        rawScore = row.rawScore,
        maxScore = row.maxScore,
        percentage = row.percentage,
        assessmentTitle = row.assessmentTitle,
        cbcStrandTag = row.cbcStrandTag,
        teacherNote = row.teacherNote,
        gradedAt = row.gradedAt.toEpochMilli(),
    )

    // ---------------------------------------------------------------- internals

    private fun assessmentForClass(clazz: TeacherClassEntity, assessmentIdRaw: String): GradebookAssessmentEntity {
        val row = assessmentRepository.findByClientId(assessmentIdRaw) ?: throw notFound("Assessment not found")
        if (row.classId != clazz.id) throw notFound("Assessment not found")
        return row
    }

    private fun classForWrite(current: CurrentUser, classIdRaw: String): TeacherClassEntity {
        val clazz = classRepository.findById(parseUuid(classIdRaw, "classId")).orElse(null)
            ?: throw notFound("Class not found")
        val user = user(current)
        val schoolWide = (user.subRole == SubRole.GRADE_COORDINATOR || user.subRole == SubRole.ICT_ADMIN) &&
            (clazz.schoolId == null || clazz.schoolId == user.schoolId)
        if (user.role != Role.ADMIN && clazz.teacherUserId != user.id && !schoolWide) {
            throw ApiException(ApiErrorCode.FORBIDDEN, "Not your class")
        }
        return clazz
    }

    private fun requireAssessmentOwner(current: CurrentUser, row: GradebookAssessmentEntity) {
        if (current.role == Role.ADMIN) return
        val user = user(current)
        if (row.createdBy == user.id) return
        if (user.subRole == SubRole.GRADE_COORDINATOR || user.subRole == SubRole.ICT_ADMIN) return
        throw ApiException(ApiErrorCode.FORBIDDEN, "Only the author or a coordinator can change this assessment")
    }

    private fun assessmentType(raw: String): GradebookAssessmentType =
        GradebookAssessmentType.entries.firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) }
            ?: throw invalidArgument("Unknown assessment type: " + raw)

    private fun user(current: CurrentUser): UserEntity =
        userRepository.findById(current.userId).orElse(null) ?: throw notFound("User not found")

    private fun parseUuid(raw: String, field: String): UUID =
        runCatching { UUID.fromString(raw) }.getOrNull() ?: throw invalidArgument(field + " is not a valid identifier")
}
