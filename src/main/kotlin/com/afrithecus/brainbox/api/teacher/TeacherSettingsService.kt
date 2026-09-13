package com.afrithecus.brainbox.api.teacher

import com.afrithecus.brainbox.api.classes.repository.ClassMembershipRepository
import com.afrithecus.brainbox.api.classes.repository.TeacherClassRepository
import com.afrithecus.brainbox.api.common.error.ApiErrorCode
import com.afrithecus.brainbox.api.common.error.ApiException
import com.afrithecus.brainbox.api.exams.QuestionCodec
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.model.Role
import com.afrithecus.brainbox.api.identity.model.SubRole
import com.afrithecus.brainbox.api.identity.repository.SchoolRepository
import com.afrithecus.brainbox.api.identity.repository.TeacherCodeRepository
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import com.afrithecus.brainbox.api.teacher.entity.TeacherSettingsEntity
import com.afrithecus.brainbox.api.teacher.repository.TeacherSettingsRepository
import com.afrithecus.brainbox.api.teacher.web.TeacherNotificationPrefsPayload
import com.afrithecus.brainbox.api.teacher.web.TeacherProfilePayload
import com.afrithecus.brainbox.api.teacher.web.TeacherSettingsPayload
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.util.UUID

/**
 * Teacher settings and profile (doc 04 teacher settings): the app's preference
 * document plus the account/profile shape. Self-scoped to the signed-in teacher;
 * the teacherId query is ignored.
 */
@Service
class TeacherSettingsService(
    private val settingsRepository: TeacherSettingsRepository,
    private val userRepository: UserRepository,
    private val schoolRepository: SchoolRepository,
    private val teacherCodeRepository: TeacherCodeRepository,
    private val classRepository: TeacherClassRepository,
    private val membershipRepository: ClassMembershipRepository,
    private val codec: QuestionCodec,
    private val mapper: ObjectMapper,
) {

    @Transactional
    fun settings(teacher: UserEntity): TeacherSettingsPayload {
        requireTeacher(teacher)
        return settingsPayload(teacher, settingsRepository.findByTeacherId(teacher.id))
    }

    @Transactional
    fun updateSettings(teacher: UserEntity, request: TeacherSettingsPayload): TeacherSettingsPayload {
        requireTeacher(teacher)
        val entity = settingsRepository.findByTeacherId(teacher.id)
            ?: TeacherSettingsEntity().apply { teacherId = teacher.id }
        applySettings(entity, request)
        settingsRepository.saveAndFlush(entity)
        val name = request.name.trim()
        if (name.isNotEmpty() && name != teacher.name) {
            teacher.name = name
            userRepository.save(teacher)
        }
        return settingsPayload(teacher, entity)
    }

    @Transactional(readOnly = true)
    fun profile(teacher: UserEntity): TeacherProfilePayload {
        requireTeacher(teacher)
        return profilePayload(teacher)
    }

    @Transactional
    fun updateProfile(teacher: UserEntity, request: TeacherProfilePayload): TeacherProfilePayload {
        requireTeacher(teacher)
        val name = request.name.trim()
        if (name.isNotEmpty()) teacher.name = name
        userRepository.save(teacher)

        val settings = settingsRepository.findByTeacherId(teacher.id)
            ?: TeacherSettingsEntity().apply { teacherId = teacher.id }
        request.subjects?.let { settings.subjectsTaught = codec.toJson(it.filter { s -> s.isNotBlank() }) }
        request.tscNumber?.let { settings.tscNumber = it.trim().takeIf { value -> value.isNotEmpty() } }
        request.gradesTaught?.let { grades ->
            if (settings.defaultGradeLevel == null) {
                settings.defaultGradeLevel = grades.firstOrNull()?.let { raw ->
                    Regex("""\d{1,2}""").find(raw)?.value?.toIntOrNull()
                }
            }
        }
        settingsRepository.saveAndFlush(settings)
        return profilePayload(teacher)
    }

    // ------------------------------------------------------------ internals

    private fun settingsPayload(teacher: UserEntity, entity: TeacherSettingsEntity?): TeacherSettingsPayload {
        val school = teacher.schoolId?.let { schoolRepository.findById(it).orElse(null) }
        return TeacherSettingsPayload(
            teacherId = teacher.id.toString(),
            name = teacher.name,
            phone = teacher.phoneNumber.orEmpty(),
            email = teacher.email,
            schoolId = teacher.schoolId?.toString() ?: "",
            schoolName = school?.name ?: "",
            subjectsTaught = entity?.let { codec.parseList(it.subjectsTaught) } ?: emptyList(),
            tscNumber = entity?.tscNumber,
            defaultGradeLevel = entity?.defaultGradeLevel,
            languagePreference = entity?.languagePreference ?: "en",
            themePreference = entity?.themePreference ?: "dark",
            autoAttendance = entity?.autoAttendance ?: true,
            notificationsEnabled = entity?.notificationsEnabled ?: true,
            defaultGradeWeighting = parseWeighting(entity?.defaultGradeWeighting),
            notificationPreferences = TeacherNotificationPrefsPayload(
                assignmentSubmissionAlerts = entity?.assignmentSubmissionAlerts ?: true,
                newExamPublish = entity?.newExamPublish ?: true,
                parentMessages = entity?.parentMessages ?: true,
                staffBulletin = entity?.staffBulletin ?: true,
                emailNotifications = entity?.emailNotifications ?: false,
            ),
        )
    }

    private fun applySettings(entity: TeacherSettingsEntity, request: TeacherSettingsPayload) {
        entity.subjectsTaught = codec.toJson(request.subjectsTaught.filter { it.isNotBlank() })
        entity.tscNumber = request.tscNumber?.trim()?.takeIf { it.isNotEmpty() }
        entity.defaultGradeLevel = request.defaultGradeLevel
        entity.languagePreference = request.languagePreference.trim().ifEmpty { "en" }
        entity.themePreference = request.themePreference.trim().ifEmpty { "dark" }
        entity.autoAttendance = request.autoAttendance
        entity.notificationsEnabled = request.notificationsEnabled
        entity.defaultGradeWeighting = mapper.writeValueAsString(request.defaultGradeWeighting)
        entity.assignmentSubmissionAlerts = request.notificationPreferences.assignmentSubmissionAlerts
        entity.newExamPublish = request.notificationPreferences.newExamPublish
        entity.parentMessages = request.notificationPreferences.parentMessages
        entity.staffBulletin = request.notificationPreferences.staffBulletin
        entity.emailNotifications = request.notificationPreferences.emailNotifications
    }

    private fun profilePayload(teacher: UserEntity): TeacherProfilePayload {
        val settings = settingsRepository.findByTeacherId(teacher.id)
        val code = teacherCodeRepository.findByTeacherUserIdAndActiveTrue(teacher.id)?.code
        val school = teacher.schoolId?.let { schoolRepository.findById(it).orElse(null) }
        val classes = classRepository.findAllByTeacherUserIdAndIsActiveTrueOrderByNameAsc(teacher.id)
        val studentCount = classes.sumOf { membershipRepository.countByClassId(it.id).toInt() }
        val subjects = codec.parseList(settings?.subjectsTaught).orEmpty()
            .ifEmpty { classes.map { it.subject }.distinct() }
        val grades = classes.map { it.gradeLevel }.distinct()
        return TeacherProfilePayload(
            id = teacher.id.toString(),
            phoneNumber = teacher.phoneNumber.orEmpty(),
            name = teacher.name,
            role = clientRole(teacher.role),
            schoolId = teacher.schoolId?.toString() ?: "",
            schoolName = school?.name ?: "",
            studentAdmissionNumber = teacher.studentAdmissionNumber,
            grade = teacher.gradeLevel,
            parentId = teacher.parentUserId?.toString(),
            createdAt = teacher.createdAt.toEpochMilli(),
            lastLogin = teacher.lastLogin?.toEpochMilli() ?: 0,
            isActive = teacher.isActive,
            teacherCode = code,
            gradesTaught = grades.ifEmpty { null },
            className = classes.firstOrNull()?.name,
            studentCount = studentCount.takeIf { classes.isNotEmpty() },
            subjects = subjects.ifEmpty { null },
            tscNumber = settings?.tscNumber,
            gradeAssignments = classes.map { it.name }.ifEmpty { null },
            gradeLevelAssignments = grades.ifEmpty { null },
            onboardingCompleted = code != null && teacher.schoolId != null,
            managedSchoolId = if (teacher.subRole == SubRole.ICT_ADMIN) teacher.schoolId?.toString() else null,
            verificationStatus = teacher.verificationStatus.name,
            ctcFrozen = teacherCodeRepository.findByTeacherUserIdAndActiveTrue(teacher.id)?.frozen ?: false,
        )
    }

    private fun parseWeighting(json: String?): Map<String, Double> {
        if (json.isNullOrBlank()) return emptyMap()
        val node = runCatching { mapper.readTree(json) }.getOrNull() ?: return emptyMap()
        if (!node.isObject) return emptyMap()
        return node.properties().associate { it.key to it.value.asDouble() }
    }

    private fun clientRole(role: Role): String = when (role) {
        Role.TEACHER, Role.ADMIN -> "TEACHER"
        Role.STUDENT -> "STUDENT"
        Role.PARENT -> "PARENT"
    }

    private fun requireTeacher(user: UserEntity) {
        if (user.role != Role.TEACHER) throw ApiException(ApiErrorCode.FORBIDDEN, "Teacher access only")
    }
}
