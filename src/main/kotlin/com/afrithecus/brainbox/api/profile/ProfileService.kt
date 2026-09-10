package com.afrithecus.brainbox.api.profile

import com.afrithecus.brainbox.api.common.error.conflict
import com.afrithecus.brainbox.api.common.error.invalidArgument
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.model.Role
import com.afrithecus.brainbox.api.identity.model.SubRole
import com.afrithecus.brainbox.api.identity.model.SubscriptionTier
import com.afrithecus.brainbox.api.identity.repository.SchoolRepository
import com.afrithecus.brainbox.api.identity.repository.TeacherCodeRepository
import com.afrithecus.brainbox.api.identity.repository.TeacherProfileRepository
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import com.afrithecus.brainbox.api.profile.entity.UserSettingsEntity
import com.afrithecus.brainbox.api.profile.repository.UserSettingsRepository
import com.afrithecus.brainbox.api.profile.web.UpdateSettingsRequest
import com.afrithecus.brainbox.api.profile.web.UserSettingsPayload
import com.afrithecus.brainbox.api.subscription.SubscriptionService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper

/**
 * Builds and mutates the profile/settings payload. Read-only fields (plan,
 * subscription state, teacher code, role label) are always derived server-side
 * from identity/subscription rows, never from the request (doc 01 §4.3).
 */
@Service
class ProfileService(
    private val settingsRepository: UserSettingsRepository,
    private val userRepository: UserRepository,
    private val schoolRepository: SchoolRepository,
    private val teacherProfileRepository: TeacherProfileRepository,
    private val teacherCodeRepository: TeacherCodeRepository,
    private val subscriptionService: SubscriptionService,
    private val mapper: ObjectMapper,
) {

    @Transactional
    fun settings(user: UserEntity): UserSettingsPayload = payloadFor(user, rowFor(user.id))

    @Transactional
    fun update(user: UserEntity, request: UpdateSettingsRequest): UserSettingsPayload {
        val row = rowFor(user.id)
        applyIdentity(user, request, row)
        applyPreferences(row, request)
        userRepository.save(user)
        return payloadFor(user, settingsRepository.save(row))
    }

    // ------------------------------------------------------------ internals

    private fun rowFor(userId: java.util.UUID): UserSettingsEntity =
        settingsRepository.findByUserId(userId) ?: UserSettingsEntity().apply { this.userId = userId }

    private fun applyIdentity(user: UserEntity, request: UpdateSettingsRequest, row: UserSettingsEntity) {
        request.fullName?.takeIf { it.isNotBlank() }?.let { name -> user.name = name.trim() }
        request.email?.let { email ->
            val normalized = email.trim().lowercase()
            val owner = userRepository.findByEmail(normalized)
            if (owner != null && owner.id != user.id) throw conflict("Email is already in use")
            user.email = normalized
        }
        request.grade?.let { user.gradeLevel = it.trim() }
        request.admissionNumber?.let { admission ->
            val trimmed = admission.trim()
            val owner = userRepository.findByStudentAdmissionNumber(trimmed)
            if (owner != null && owner.id != user.id) throw conflict("Admission number is already in use")
            user.studentAdmissionNumber = trimmed.ifBlank { null }
        }
        request.school?.let { name ->
            val trimmed = name.trim()
            if (trimmed.isBlank()) {
                user.schoolId = null
            } else {
                val school = schoolRepository.findByNameIgnoreCase(trimmed)
                    ?: throw invalidArgument("Unknown school: " + trimmed)
                user.schoolId = school.id
            }
        }
        request.subjects?.let { row.subjects = mapper.writeValueAsString(it.map(String::trim).filter(String::isNotEmpty)) }
        request.interestSubjects?.let { row.interestSubjects = mapper.writeValueAsString(it) }
    }

    private fun applyPreferences(row: UserSettingsEntity, request: UpdateSettingsRequest) {
        request.dailyReminderEnabled?.let { row.dailyReminderEnabled = it }
        request.dailyReminderTime?.let { row.dailyReminderTime = it }
        request.weeklyReportEnabled?.let { row.weeklyReportEnabled = it }
        request.preferredDifficulty?.let { row.preferredDifficulty = it }
        request.showOnLeaderboard?.let { row.showOnLeaderboard = it }
        request.shareProgressWithSchool?.let { row.shareProgressWithSchool = it }
        request.allowTeacherView?.let { row.allowTeacherView = it }
        request.pushNotificationsEnabled?.let { row.pushNotificationsEnabled = it }
        request.pushContestReminders?.let { row.pushContestReminders = it }
        request.pushAssignmentDue?.let { row.pushAssignmentDue = it }
        request.pushQuizResults?.let { row.pushQuizResults = it }
        request.pushWeeklyReport?.let { row.pushWeeklyReport = it }
        request.pushBadges?.let { row.pushBadges = it }
        request.smsReportsEnabled?.let { row.smsReportsEnabled = it }
        request.emailNotificationsEnabled?.let { row.emailNotificationsEnabled = it }
        request.mpesaNumber?.let { row.mpesaNumber = it }
        request.fontSize?.let { row.fontSize = it }
        request.animationsEnabled?.let { row.animationsEnabled = it }
        request.hapticFeedbackEnabled?.let { row.hapticFeedbackEnabled = it }
        request.personalizedContentEnabled?.let { row.personalizedContentEnabled = it }
        request.aiAdaptiveDifficultyEnabled?.let { row.aiAdaptiveDifficultyEnabled = it }
        request.aiSensitivity?.let { row.aiSensitivity = it }
        request.aiCoachingStyle?.let { row.aiCoachingStyle = it }
        request.cbcPathway?.let { row.cbcPathway = it }
        request.competencyFocus?.let { row.competencyFocus = mapper.writeValueAsString(it) }
    }

    private fun payloadFor(user: UserEntity, row: UserSettingsEntity): UserSettingsPayload {
        val subscription = subscriptionService.view(user.id)
        val teacherProfile = teacherProfileRepository.findByUserId(user.id)
        val teacherCode = teacherCodeRepository.findByTeacherUserIdAndActiveTrue(user.id)?.code
        val subjects = parseStringList(row.subjects).ifEmpty {
            teacherProfile?.subject?.takeIf { it.isNotBlank() }?.let { listOf(it) } ?: emptyList()
        }
        return UserSettingsPayload(
            fullName = user.name,
            phoneNumber = user.phoneNumber ?: "",
            email = user.email,
            avatarUrl = row.avatarUrl,
            school = user.schoolId?.let { schoolRepository.findById(it).map { s -> s.name }.orElse("") } ?: "",
            grade = user.gradeLevel ?: "",
            subjects = subjects,
            admissionNumber = user.studentAdmissionNumber ?: "",
            dailyReminderEnabled = row.dailyReminderEnabled,
            dailyReminderTime = row.dailyReminderTime,
            weeklyReportEnabled = row.weeklyReportEnabled,
            preferredDifficulty = row.preferredDifficulty,
            interestSubjects = parseStringList(row.interestSubjects),
            showOnLeaderboard = row.showOnLeaderboard,
            shareProgressWithSchool = row.shareProgressWithSchool,
            allowTeacherView = row.allowTeacherView,
            pushNotificationsEnabled = row.pushNotificationsEnabled,
            pushContestReminders = row.pushContestReminders,
            pushAssignmentDue = row.pushAssignmentDue,
            pushQuizResults = row.pushQuizResults,
            pushWeeklyReport = row.pushWeeklyReport,
            pushBadges = row.pushBadges,
            smsReportsEnabled = row.smsReportsEnabled,
            emailNotificationsEnabled = row.emailNotificationsEnabled,
            plan = subscription.tier,
            subscriptionStatus = subscription.status,
            subscriptionExpiry = if (subscription.tier != SubscriptionTier.BASE.name) subscription.expiryDate else null,
            mpesaNumber = row.mpesaNumber,
            theme = row.theme,
            fontSize = row.fontSize,
            animationsEnabled = row.animationsEnabled,
            hapticFeedbackEnabled = row.hapticFeedbackEnabled,
            personalizedContentEnabled = row.personalizedContentEnabled,
            aiAdaptiveDifficultyEnabled = row.aiAdaptiveDifficultyEnabled,
            aiSensitivity = row.aiSensitivity,
            aiCoachingStyle = row.aiCoachingStyle,
            cbcPathway = row.cbcPathway,
            competencyFocus = parseIntMap(row.competencyFocus).ifEmpty { DEFAULT_COMPETENCY_FOCUS },
            tscNumber = row.tscNumber,
            teacherCode = teacherCode,
            gradesTaught = parseStringList(row.gradesTaught),
            roleLabel = roleLabel(user),
        )
    }

    private fun roleLabel(user: UserEntity): String = when (user.role) {
        Role.STUDENT -> "Student"
        Role.PARENT -> "Parent"
        Role.ADMIN -> "Administrator"
        Role.TEACHER -> when (user.subRole) {
            SubRole.CTEACHER -> "Class Teacher"
            SubRole.GRADE_COORDINATOR -> "Grade Coordinator"
            SubRole.ICT_ADMIN -> "ICT Admin"
            null -> "Teacher"
        }
    }

    private fun parseStringList(json: String?): List<String> {
        if (json.isNullOrBlank()) return emptyList()
        val node = runCatching { mapper.readTree(json) }.getOrNull() ?: return emptyList()
        if (!node.isArray) return emptyList()
        return (0 until node.size()).map { node.get(it).asString() }
    }

    private fun parseIntMap(json: String?): Map<String, Int> {
        if (json.isNullOrBlank()) return emptyMap()
        val node = runCatching { mapper.readTree(json) }.getOrNull() ?: return emptyMap()
        if (!node.isObject) return emptyMap()
        val out = LinkedHashMap<String, Int>()
        for (entry in node.properties()) out[entry.key] = entry.value.intValue()
        return out
    }

    private companion object {
        val DEFAULT_COMPETENCY_FOCUS = linkedMapOf(
            "Critical Thinking" to 50,
            "Communication" to 50,
            "Collaboration" to 50,
            "Creativity" to 50,
        )
    }
}
