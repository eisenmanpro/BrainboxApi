package com.afrithecus.brainbox.api.identity.web

import com.afrithecus.brainbox.api.auth.web.UserPayload
import com.afrithecus.brainbox.api.classes.repository.ClassMembershipRepository
import com.afrithecus.brainbox.api.classes.repository.TeacherClassRepository
import com.afrithecus.brainbox.api.exams.QuestionCodec
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.model.Role
import com.afrithecus.brainbox.api.identity.model.SubRole
import com.afrithecus.brainbox.api.identity.repository.SchoolRepository
import com.afrithecus.brainbox.api.identity.repository.TeacherCodeRepository
import com.afrithecus.brainbox.api.teacher.repository.TeacherSettingsRepository
import org.springframework.stereotype.Component

/**
 * Maps a user to the contract UserPayload (doc 01 §2.1) with school name join.
 * Teacher accounts also carry the derived teaching view (CTC, classes, subjects,
 * verification state) the client's User model expects
 * (docs/ongoing/api_teacher_roster_changes.md).
 */
@Component
class UserPayloadFactory(
    private val schoolRepository: SchoolRepository,
    private val teacherCodeRepository: TeacherCodeRepository,
    private val teacherSettingsRepository: TeacherSettingsRepository,
    private val classRepository: TeacherClassRepository,
    private val membershipRepository: ClassMembershipRepository,
    private val codec: QuestionCodec,
) {

    fun toPayload(user: UserEntity): UserPayload {
        val schoolName = user.schoolId?.let { id ->
            schoolRepository.findById(id).map { it.name }.orElse(null)
        }
        val base = UserPayload(
            id = user.id.toString(),
            phoneNumber = user.phoneNumber,
            name = user.name,
            role = user.role.name,
            subRole = user.subRole?.name,
            schoolId = user.schoolId?.toString(),
            schoolName = schoolName,
            studentAdmissionNumber = user.studentAdmissionNumber,
            parentId = user.parentUserId?.toString(),
            childId = null,
            referredByTeacherCode = user.referredByTeacherCode,
            joinedTeacherId = user.joinedTeacherId?.toString(),
            gradeLevel = user.gradeLevel,
            isActive = user.isActive,
            isVerified = user.isVerified,
            createdAt = user.createdAt.toEpochMilli(),
            lastLogin = user.lastLogin?.toEpochMilli(),
            verificationStatus = user.verificationStatus.name,
        )
        if (user.role != Role.TEACHER) return base
        return base.copy(
            teacherSubRole = user.subRole?.name ?: "TEACHER",
            managedSchoolId = if (user.subRole == SubRole.ICT_ADMIN) user.schoolId?.toString() else null,
            verificationStatus = user.verificationStatus.name,
            ctcFrozen = teacherCodeRepository.findByTeacherUserIdAndActiveTrue(user.id)?.frozen ?: false,
            teacherCode = teacherCodeRepository.findByTeacherUserIdAndActiveTrue(user.id)?.code,
            gradesTaught = teachingGrades(user.id),
            className = teachingClasses(user.id).firstOrNull()?.name,
            studentCount = teachingClasses(user.id).takeIf { it.isNotEmpty() }
                ?.sumOf { membershipRepository.countByClassId(it.id).toInt() },
            subjects = teachingSubjects(user.id),
            tscNumber = teacherSettingsRepository.findByTeacherId(user.id)?.tscNumber,
            gradeAssignments = teachingClasses(user.id).map { it.name }.ifEmpty { null },
            gradeLevelAssignments = teachingGrades(user.id),
            onboardingCompleted = teacherCodeRepository.existsByTeacherUserId(user.id) && user.schoolId != null,
        )
    }

    private fun teachingClasses(userId: java.util.UUID) =
        classRepository.findAllByTeacherUserIdAndIsActiveTrueOrderByNameAsc(userId)

    private fun teachingGrades(userId: java.util.UUID) =
        teachingClasses(userId).map { it.gradeLevel }.distinct().ifEmpty { null }

    private fun teachingSubjects(userId: java.util.UUID): List<String>? {
        val configured = codec.parseList(teacherSettingsRepository.findByTeacherId(userId)?.subjectsTaught).orEmpty()
        return configured.ifEmpty { teachingClasses(userId).map { it.subject }.distinct() }.ifEmpty { null }
    }
}
