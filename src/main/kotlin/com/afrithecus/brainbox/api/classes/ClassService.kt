package com.afrithecus.brainbox.api.classes

import com.afrithecus.brainbox.api.classes.entity.ClassMembershipEntity
import com.afrithecus.brainbox.api.classes.entity.TeacherClassEntity
import com.afrithecus.brainbox.api.classes.repository.ClassMembershipRepository
import com.afrithecus.brainbox.api.classes.repository.TeacherClassRepository
import com.afrithecus.brainbox.api.classes.web.AddStudentsRequest
import com.afrithecus.brainbox.api.classes.web.CreateClassRequest
import com.afrithecus.brainbox.api.classes.web.StudentInClassPayload
import com.afrithecus.brainbox.api.classes.web.TeacherClassPayload
import com.afrithecus.brainbox.api.cbcratings.CbcAnalyticsService
import com.afrithecus.brainbox.api.cbcratings.web.CbcStrandMasteryPayload
import com.afrithecus.brainbox.api.common.error.ApiErrorCode
import com.afrithecus.brainbox.api.common.error.ApiException
import com.afrithecus.brainbox.api.common.error.conflict
import com.afrithecus.brainbox.api.common.error.invalidArgument
import com.afrithecus.brainbox.api.common.error.notFound
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.model.Role
import com.afrithecus.brainbox.api.achievements.AchievementsService
import com.afrithecus.brainbox.api.gradebook.entity.GradebookEntryEntity
import com.afrithecus.brainbox.api.gradebook.repository.GradebookEntryRepository
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID
import kotlin.math.roundToInt

/**
 * Teacher classes & roster (doc 04 §2.2). Ownership rule: a teacher may only
 * act on classes they own (403 otherwise); students enroll via roster additions
 * and can read their own classes.
 */
@Service
class ClassService(
    private val classRepository: TeacherClassRepository,
    private val membershipRepository: ClassMembershipRepository,
    private val userRepository: UserRepository,
    private val gradebookEntryRepository: GradebookEntryRepository,
    private val achievementsService: AchievementsService,
    private val cbcAnalytics: CbcAnalyticsService,
) {

    // ------------------------------------------------------------- teacher

    @Transactional
    fun createClass(teacher: UserEntity, request: CreateClassRequest): TeacherClassPayload {
        requireTeacher(teacher)
        val schoolId = teacher.schoolId
            ?: throw ApiException(ApiErrorCode.FORBIDDEN, "A teacher must belong to a school to create classes")
        val entity = TeacherClassEntity().apply {
            teacherUserId = teacher.id
            this.schoolId = schoolId
            name = request.name.trim()
            gradeLevel = request.grade.trim()
            subject = request.subject.trim()
            isActive = true
        }
        classRepository.save(entity)
        return toPayload(entity)
    }

    @Transactional(readOnly = true)
    fun teacherClasses(teacher: UserEntity): List<TeacherClassPayload> {
        requireTeacher(teacher)
        return classRepository.findAllByTeacherUserIdAndIsActiveTrueOrderByNameAsc(teacher.id).map(::toPayload)
    }

    @Transactional(readOnly = true)
    fun classStudents(teacher: UserEntity, classIdRaw: String): List<StudentInClassPayload> {
        requireTeacher(teacher)
        val clazz = ownClass(teacher, classIdRaw)
        val members = membershipRepository.findAllByClassId(clazz.id)
        val students = userRepository.findAllById(members.map { it.studentId })
            .associateBy { it.id }
        if (students.isEmpty()) return emptyList()
        val entriesByStudent = gradebookEntryRepository.findAllByStudentIdIn(students.keys).groupBy { it.studentId }
        val strandMastery = runCatching { cbcAnalytics.classReport(teacher, classIdRaw, "") }
            .getOrNull()?.strandMastery.orEmpty()
        return members.mapNotNull { membership ->
            students[membership.studentId]?.let { student ->
                toStudent(student, entriesByStudent[student.id].orEmpty(), strandMastery)
            }
        }.sortedBy { it.name.lowercase() }
    }

    @Transactional
    fun addStudents(teacher: UserEntity, classIdRaw: String, request: AddStudentsRequest) {
        requireTeacher(teacher)
        val clazz = ownClass(teacher, classIdRaw)
        request.studentIds.forEach { raw ->
            val studentId = parseUuid(raw, "studentIds")
            val student = userRepository.findById(studentId).orElse(null)
                ?: throw notFound("Student not found")
            if (student.role != Role.STUDENT) throw invalidArgument("Only STUDENT accounts can join a class roster")
            if (clazz.schoolId != null && student.schoolId != null && clazz.schoolId != student.schoolId) {
                throw invalidArgument("Student is not enrolled in this school")
            }
            if (membershipRepository.findByClassIdAndStudentId(clazz.id, studentId) == null) {
                membershipRepository.save(
                    ClassMembershipEntity().apply {
                        this.classId = clazz.id
                        this.studentId = studentId
                    }
                )
            }
        }
    }

    @Transactional
    fun removeStudent(teacher: UserEntity, classIdRaw: String, studentIdRaw: String) {
        requireTeacher(teacher)
        val clazz = ownClass(teacher, classIdRaw)
        membershipRepository.deleteByClassIdAndStudentId(clazz.id, parseUuid(studentIdRaw, "studentId"))
    }

    // ------------------------------------------------------------- student

    @Transactional(readOnly = true)
    fun myClasses(student: UserEntity): List<TeacherClassPayload> {
        if (student.role != Role.STUDENT) {
            throw ApiException(ApiErrorCode.FORBIDDEN, "Only students have a class list")
        }
        val memberships = membershipRepository.findAllByStudentId(student.id)
        if (memberships.isEmpty()) return emptyList()
        val classes = classRepository.findAllById(memberships.map { it.classId })
            .filter { it.isActive }
            .associateBy { it.id }
        return memberships.mapNotNull { membership -> classes[membership.classId]?.let(::toPayload) }
            .sortedBy { it.name.lowercase() }
    }

    // ------------------------------------------------------------ internals

    private fun ownClass(teacher: UserEntity, raw: String): TeacherClassEntity {
        val id = parseUuid(raw, "classId")
        val clazz = classRepository.findById(id).orElse(null) ?: throw notFound("Class not found")
        if (clazz.teacherUserId != teacher.id) {
            throw ApiException(ApiErrorCode.FORBIDDEN, "Not your class")
        }
        return clazz
    }

    private fun requireTeacher(user: UserEntity) {
        if (user.role != Role.TEACHER) {
            throw ApiException(ApiErrorCode.FORBIDDEN, "Teacher access only")
        }
    }

    private fun toPayload(clazz: TeacherClassEntity) = TeacherClassPayload(
        classId = clazz.id.toString(),
        name = clazz.name,
        grade = clazz.gradeLevel,
        subject = clazz.subject,
        studentCount = membershipRepository.countByClassId(clazz.id).toInt(),
    )

    private fun toStudent(
        student: UserEntity,
        entries: List<GradebookEntryEntity>,
        strandMastery: List<CbcStrandMasteryPayload>,
    ) = StudentInClassPayload(
        id = student.id.toString(),
        name = student.name,
        admissionNumber = student.studentAdmissionNumber,
        grade = gradeNumber(student.gradeLevel),
        averageScore = if (entries.isEmpty()) 0 else entries.map { it.percentage }.average().roundToInt(),
        currentStreak = runCatching { achievementsService.forUser(student.id).currentStreak }.getOrDefault(0),
        lastActive = student.lastLogin?.toEpochMilli() ?: 0L,
        parentId = student.parentUserId?.toString(),
        cbcCompetencySummary = strandMastery.mapNotNull { strand ->
            strand.studentBreakdown[student.id.toString()]?.let { strand.strandName to it }
        }.toMap(),
    )

    private fun gradeNumber(raw: String?): Int =
        Regex("\\d+").find(raw ?: "")?.value?.toIntOrNull() ?: 0

    private fun parseUuid(raw: String, field: String): UUID =
        runCatching { UUID.fromString(raw) }.getOrNull()
            ?: throw invalidArgument(field + " is not a valid identifier")
}
