package com.afrithecus.brainbox.api.cbcratings

import com.afrithecus.brainbox.api.attendance.model.AttendanceStatus
import com.afrithecus.brainbox.api.attendance.repository.AttendanceRecordRepository
import com.afrithecus.brainbox.api.cbcratings.entity.CbcRatingEntity
import com.afrithecus.brainbox.api.cbcratings.repository.CbcRatingRepository
import com.afrithecus.brainbox.api.cbcratings.repository.CbcStrandRepository
import com.afrithecus.brainbox.api.cbcratings.web.CbcClassReportPayload
import com.afrithecus.brainbox.api.cbcratings.web.CbcCurriculumMapPayload
import com.afrithecus.brainbox.api.cbcratings.web.CbcReportCardPayload
import com.afrithecus.brainbox.api.cbcratings.web.CbcStrandInfoPayload
import com.afrithecus.brainbox.api.cbcratings.web.CbcStrandMasteryPayload
import com.afrithecus.brainbox.api.cbcratings.web.CbcStrandRatingPayload
import com.afrithecus.brainbox.api.cbcratings.web.StrandMasteryDetailPayload
import com.afrithecus.brainbox.api.cbcratings.web.StudentStrandRatingPayload
import com.afrithecus.brainbox.api.cbcratings.web.SubjectTeacherPerformancePayload
import com.afrithecus.brainbox.api.classes.repository.ClassMembershipRepository
import com.afrithecus.brainbox.api.classes.repository.TeacherClassRepository
import com.afrithecus.brainbox.api.common.error.ApiErrorCode
import com.afrithecus.brainbox.api.common.error.ApiException
import com.afrithecus.brainbox.api.common.error.invalidArgument
import com.afrithecus.brainbox.api.common.error.notFound
import com.afrithecus.brainbox.api.feedback.repository.TeacherFeedbackRepository
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.model.Role
import com.afrithecus.brainbox.api.identity.model.SubRole
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID

/**
 * Teacher CBC analytics (doc 04 CBC analytics): the curriculum-map catalogue,
 * class and student CBC reports, strand detail and the rating upsert. Ratings
 * are stored per (student, strand, term) so a re-rating corrects the record.
 */
@Service
class CbcAnalyticsService(
    private val strandRepository: CbcStrandRepository,
    private val ratingRepository: CbcRatingRepository,
    private val classRepository: TeacherClassRepository,
    private val membershipRepository: ClassMembershipRepository,
    private val userRepository: UserRepository,
    private val feedbackRepository: TeacherFeedbackRepository,
    private val attendanceRepository: AttendanceRecordRepository,
    private val clock: Clock,
) {

    @Transactional(readOnly = true)
    fun curriculumMap(): CbcCurriculumMapPayload = CbcCurriculumMapPayload(
        strands = strandRepository.findAllByOrderBySortOrderAsc().map {
            CbcStrandInfoPayload(it.code, it.name, it.descriptor, it.gradeLevel)
        },
    )

    @Transactional
    fun inputRating(
        teacher: UserEntity,
        studentIdRaw: String,
        strandCodeRaw: String,
        termRaw: String,
        ratingRaw: String,
        evidence: String?,
        comments: String?,
    ) {
        requireTeacher(teacher)
        val student = userRepository.findById(parseUuid(studentIdRaw, "studentId")).orElse(null)
            ?: throw notFound("Learner not found")
        requireStudentAccess(teacher, student)
        val strandCode = strandCodeRaw.trim().uppercase()
        strandRepository.findByCode(strandCode) ?: throw notFound("Strand not found")
        val rating = ratingRaw.trim().uppercase()
        if (rating !in RATINGS) throw invalidArgument("Unknown CBC rating: " + ratingRaw)
        val term = termRaw.trim().ifEmpty { "TERM_1" }
        val entity = ratingRepository.findByStudentIdAndStrandCodeAndTerm(student.id, strandCode, term)
            ?: CbcRatingEntity().apply {
                studentId = student.id
                this.strandCode = strandCode
                this.term = term
            }
        entity.teacherId = teacher.id
        entity.rating = rating
        entity.evidence = evidence?.trim()?.takeIf { it.isNotEmpty() }
        entity.comments = comments?.trim()?.takeIf { it.isNotEmpty() }
        entity.ratedAt = clock.instant()
        ratingRepository.saveAndFlush(entity)
    }

    @Transactional(readOnly = true)
    fun classReport(teacher: UserEntity, classIdRaw: String, termRaw: String): CbcClassReportPayload {
        requireTeacher(teacher)
        val clazz = requireClassAccess(teacher, classIdRaw)
        val term = termRaw.trim().ifEmpty { "TERM_1" }
        val studentIds = membershipRepository.findAllByClassId(clazz.id).map { it.studentId }
        val ratings = if (studentIds.isEmpty()) {
            emptyList()
        } else {
            ratingRepository.findAllByStudentIdInAndTerm(studentIds, term)
        }
        val strands = strandRepository.findAllByCodeIn(ratings.map { it.strandCode }.toSet()).associateBy { it.code }
        val mastery = ratings.groupBy { it.strandCode }.entries.map { (code, rows) ->
            val name = strands[code]?.name ?: code
            val scores = rows.map { ratingScore(it.rating) }
            val average = scores.average()
            CbcStrandMasteryPayload(
                strandCode = code,
                strandName = name,
                classAverage = round2(average),
                studentBreakdown = rows.associate { it.studentId.toString() to masteryLevel(ratingScore(it.rating)) },
                isWeakStrand = average < WEAK_THRESHOLD,
                recommendation = if (average < WEAK_THRESHOLD) "Reteach and practise " + name else null,
            )
        }.sortedBy { it.classAverage }
        val overall = if (mastery.isEmpty()) 0.0 else mastery.map { it.classAverage }.average()
        return CbcClassReportPayload(
            classId = clazz.id.toString(),
            className = clazz.name,
            term = term,
            strandMastery = mastery,
            overallClassAverage = round2(overall),
            subjectTeacherPerformance = listOf(
                SubjectTeacherPerformancePayload(
                    subject = clazz.subject,
                    teacherId = clazz.teacherUserId.toString(),
                    teacherName = userRepository.findById(clazz.teacherUserId).orElse(null)?.name ?: "",
                    classAverage = round2(overall),
                    studentCount = studentIds.size,
                    strandScores = mastery.associate { it.strandCode to it.classAverage },
                ),
            ),
            generatedAt = clock.instant().toEpochMilli(),
        )
    }

    @Transactional(readOnly = true)
    fun studentReport(teacher: UserEntity, studentIdRaw: String, termRaw: String): CbcReportCardPayload {
        requireTeacher(teacher)
        val student = userRepository.findById(parseUuid(studentIdRaw, "studentId")).orElse(null)
            ?: throw notFound("Learner not found")
        requireStudentAccess(teacher, student)
        return reportCard(student, termRaw)
    }

    /**
     * Report card for a learner/parent viewer as well as staff: a learner sees only
     * their own, a parent only a linked child, and a teacher only a learner they teach
     * (coordinators/ICT/admin may read any).
     */
    @Transactional(readOnly = true)
    fun studentReportForViewer(viewer: UserEntity, studentIdRaw: String, termRaw: String): CbcReportCardPayload {
        val student = userRepository.findById(parseUuid(studentIdRaw, "studentId")).orElse(null)
            ?: throw notFound("Learner not found")
        requireViewerAccess(viewer, student)
        return reportCard(student, termRaw)
    }

    private fun reportCard(student: UserEntity, termRaw: String): CbcReportCardPayload {
        val term = termRaw.trim().ifEmpty { "TERM_1" }
        val ratings = ratingRepository.findAllByStudentIdAndTermOrderByStrandCodeAsc(student.id, term)
        val strands = strandRepository.findAllByCodeIn(ratings.map { it.strandCode }.toSet()).associateBy { it.code }
        val strandRatings = ratings.map {
            CbcStrandRatingPayload(
                strandCode = it.strandCode,
                descriptor = strands[it.strandCode]?.descriptor ?: "",
                rating = it.rating,
                evidenceLink = it.evidence,
            )
        }
        val attendance = attendanceRepository.findAllByStudentIdOrderByAttendanceDateDesc(student.id)
        val attendancePercentage = if (attendance.isEmpty()) {
            0.0
        } else {
            attendance.count { it.status == AttendanceStatus.PRESENT || it.status == AttendanceStatus.LATE }
                .toDouble() / attendance.size * 100.0
        }
        val average = if (ratings.isEmpty()) 0.0 else ratings.map { ratingScore(it.rating) }.average()
        val comments = feedbackRepository.findAllByStudentIdOrderByCreatedAtDesc(student.id)
            .firstOrNull()?.textFeedback.orEmpty()
        return CbcReportCardPayload(
            studentName = student.name,
            term = term,
            strandRatings = strandRatings,
            teacherComments = comments,
            attendancePercentage = round2(attendancePercentage),
            overallGrade = overallGradeCode(average),
            gradeLevel = student.gradeLevel,
        )
    }

    @Transactional(readOnly = true)
    fun strandDetail(teacher: UserEntity, strandCodeRaw: String, classIdRaw: String): StrandMasteryDetailPayload {
        requireTeacher(teacher)
        val clazz = requireClassAccess(teacher, classIdRaw)
        val strand = strandRepository.findByCode(strandCodeRaw.trim().uppercase())
            ?: throw notFound("Strand not found")
        val studentIds = membershipRepository.findAllByClassId(clazz.id).map { it.studentId }
        val ratings = if (studentIds.isEmpty()) {
            emptyList()
        } else {
            ratingRepository.findAllByStudentIdInAndStrandCode(studentIds, strand.code)
        }
        val names = userRepository.findAllById(studentIds).associate { it.id to it.name }
        return StrandMasteryDetailPayload(
            strandCode = strand.code,
            strandName = strand.name,
            descriptor = strand.descriptor,
            gradeLevel = strand.gradeLevel,
            classPerformance = ratings.groupingBy { it.rating }.eachCount(),
            studentRatings = ratings.map {
                StudentStrandRatingPayload(
                    studentId = it.studentId.toString(),
                    studentName = names[it.studentId] ?: "",
                    rating = it.rating,
                    lastUpdated = it.ratedAt.toEpochMilli(),
                )
            },
        )
    }

    // ------------------------------------------------------------ internals

    private fun requireClassAccess(teacher: UserEntity, classIdRaw: String): com.afrithecus.brainbox.api.classes.entity.TeacherClassEntity {
        val clazz = classRepository.findById(parseUuid(classIdRaw, "classId")).orElse(null)
            ?: throw notFound("Class not found")
        if (clazz.teacherUserId == teacher.id) return clazz
        if (teacher.subRole == SubRole.GRADE_COORDINATOR || teacher.subRole == SubRole.ICT_ADMIN) return clazz
        throw forbidden("Not your class")
    }

    private fun requireStudentAccess(teacher: UserEntity, student: UserEntity) {
        if (teacher.subRole == SubRole.GRADE_COORDINATOR || teacher.subRole == SubRole.ICT_ADMIN) return
        val studentClasses = membershipRepository.findAllByStudentId(student.id).map { it.classId }.toSet()
        val teacherClasses = classRepository.findAllByTeacherUserIdAndIsActiveTrueOrderByNameAsc(teacher.id)
            .map { it.id }
            .toSet()
        if (studentClasses.intersect(teacherClasses).isEmpty()) throw forbidden("Not your learner")
    }
    private fun requireViewerAccess(viewer: UserEntity, student: UserEntity) {
        when (viewer.role) {
            Role.STUDENT -> if (viewer.id != student.id) throw forbidden("Not your report")
            Role.PARENT -> if (student.parentUserId != viewer.id) throw forbidden("Not your linked child")
            Role.ADMIN -> Unit
            else -> requireStudentAccess(viewer, student)
        }
    }

    private fun ratingScore(rating: String): Double = when (rating.uppercase()) {
        "EXCEEDING" -> 90.0
        "MEETING" -> 70.0
        "APPROACHING" -> 50.0
        else -> 30.0
    }

    private fun masteryLevel(score: Double): String = when {
        score >= 90 -> "MASTER"
        score >= 75 -> "ADVANCED"
        score >= 50 -> "PROFICIENT"
        score >= 25 -> "DEVELOPING"
        else -> "NOVICE"
    }

    private fun overallGradeCode(average: Double): String = when {
        average >= 80 -> "EE"
        average >= 60 -> "ME"
        average >= 40 -> "AE"
        else -> "BE"
    }

    private fun round2(value: Double): Double = Math.round(value * 100.0) / 100.0

    private fun parseUuid(raw: String, field: String): UUID =
        runCatching { UUID.fromString(raw.trim()) }.getOrNull()
            ?: throw invalidArgument(field + " is not a valid identifier")

    private fun requireTeacher(user: UserEntity) {
        if (user.role != Role.TEACHER) throw forbidden("Teacher access only")
    }

    private fun forbidden(message: String) = ApiException(ApiErrorCode.FORBIDDEN, message)

    private companion object {
        const val WEAK_THRESHOLD = 50.0
        val RATINGS = setOf("EXCEEDING", "MEETING", "APPROACHING", "BELOW")
    }
}
