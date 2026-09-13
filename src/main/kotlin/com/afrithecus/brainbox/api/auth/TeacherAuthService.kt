package com.afrithecus.brainbox.api.auth

import com.afrithecus.brainbox.api.auth.web.CtcType
import com.afrithecus.brainbox.api.auth.web.CtcValidationPayload
import com.afrithecus.brainbox.api.auth.web.RotateCtcResponsePayload
import com.afrithecus.brainbox.api.auth.web.TeacherInfoUpdateRequest
import com.afrithecus.brainbox.api.auth.web.TeacherSignupRequest
import com.afrithecus.brainbox.api.auth.web.TeacherSignupResponse
import com.afrithecus.brainbox.api.auth.web.TeacherTransferRequest
import com.afrithecus.brainbox.api.auth.web.ValidateCtcRequest
import com.afrithecus.brainbox.api.auth.web.AuthResponse
import com.afrithecus.brainbox.api.classes.entity.TeacherClassEntity
import com.afrithecus.brainbox.api.classes.repository.ClassMembershipRepository
import com.afrithecus.brainbox.api.classes.repository.TeacherClassRepository
import com.afrithecus.brainbox.api.common.error.ApiErrorCode
import com.afrithecus.brainbox.api.common.error.ApiException
import com.afrithecus.brainbox.api.common.error.conflict
import com.afrithecus.brainbox.api.common.error.invalidArgument
import com.afrithecus.brainbox.api.common.error.notFound
import com.afrithecus.brainbox.api.exams.QuestionCodec
import com.afrithecus.brainbox.api.identity.TeacherCodeGenerator
import com.afrithecus.brainbox.api.identity.entity.SchoolEntity
import com.afrithecus.brainbox.api.identity.entity.TeacherCodeEntity
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.model.AccountStatus
import com.afrithecus.brainbox.api.identity.model.CurrentUser
import com.afrithecus.brainbox.api.identity.model.Role
import com.afrithecus.brainbox.api.identity.model.SubRole
import com.afrithecus.brainbox.api.identity.repository.SchoolRepository
import com.afrithecus.brainbox.api.identity.repository.TeacherCodeRepository
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import com.afrithecus.brainbox.api.identity.web.UserPayloadFactory
import com.afrithecus.brainbox.api.subscription.SubscriptionService
import com.afrithecus.brainbox.api.teacher.entity.TeacherSettingsEntity
import com.afrithecus.brainbox.api.teacher.repository.TeacherSettingsRepository
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Teacher auth lifecycle and CTC management
 * (docs/ongoing/api_teacher_roster_changes.md): server-issued CTC on signup,
 * CTC validation/rotation/freeze, and the ICT-admin (or class-teacher for
 * students) approve/reject/freeze/transfer decisions. Repeats are idempotent so
 * the client's approval outbox can replay them.
 */
@Service
class TeacherAuthService(
    private val userRepository: UserRepository,
    private val schoolRepository: SchoolRepository,
    private val teacherCodeRepository: TeacherCodeRepository,
    private val teacherSettingsRepository: TeacherSettingsRepository,
    private val classRepository: TeacherClassRepository,
    private val membershipRepository: ClassMembershipRepository,
    private val subscriptionService: SubscriptionService,
    private val passwordEncoder: PasswordEncoder,
    private val userPayloadFactory: UserPayloadFactory,
    private val teacherCodeGenerator: TeacherCodeGenerator,
    private val authService: AuthService,
    private val codec: QuestionCodec,
) {

    // ----------------------------------------------------------- teacher signup

    @Transactional
    fun teacherSignup(request: TeacherSignupRequest, deviceId: String?): TeacherSignupResponse {
        val phone = request.phoneNumber.trim()
        if (phone.isBlank()) throw invalidArgument("phoneNumber is required")
        if (request.password.length < 8) throw invalidArgument("password must be at least 8 characters")
        if (userRepository.existsByPhoneNumber(phone)) {
            throw conflict("An account with this phone number already exists")
        }
        val school = resolveSchool(request.schoolId, request.schoolName)
        val teacher = UserEntity().apply {
            phoneNumber = phone
            name = request.name.trim()
            passwordHash = passwordEncoder.encode(request.password)
                ?: throw IllegalStateException("Password encoding failed")
            role = Role.TEACHER
            schoolId = school?.id
            isActive = true
            isVerified = false
            verificationStatus = AccountStatus.PENDING_VERIFICATION
        }
        userRepository.save(teacher)
        subscriptionService.ensure(teacher.id)

        teacherSettingsRepository.save(
            TeacherSettingsEntity().apply {
                teacherId = teacher.id
                subjectsTaught = codec.toJson(request.subjects.orEmpty().filter { it.isNotBlank() })
                tscNumber = request.tscNumber?.trim()?.takeIf { it.isNotEmpty() }
            }
        )
        if (request.className.isNotBlank()) {
            classRepository.save(
                TeacherClassEntity().apply {
                    teacherUserId = teacher.id
                    this.schoolId = teacher.schoolId
                    name = request.className.trim()
                    gradeLevel = request.grades.firstOrNull()?.trim().orEmpty()
                    subject = request.subjects?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() } ?: "General"
                    isActive = true
                }
            )
        }
        val code = issueCode(teacher)
        val auth = authService.issueAuthResponse(teacher, deviceId, includeTokens = true, message = "Teacher account created")
        return TeacherSignupResponse(
            success = true,
            message = auth.message,
            sessionToken = auth.sessionToken,
            user = auth.user,
            ctc = code,
            ctcShareText = shareText(code),
            isNewUser = true,
        )
    }

    // ------------------------------------------------------------------- CTC

    @Transactional(readOnly = true)
    fun validateCtc(request: ValidateCtcRequest): CtcValidationPayload {
        val raw = request.ctc.trim().uppercase()
        if (raw.isEmpty()) return CtcValidationPayload(isValid = false, message = "Enter a class code")
        val code = teacherCodeRepository.findByCodeAndActiveTrue(raw)
            ?: return CtcValidationPayload(isValid = false, message = "Unknown or inactive class code")
        val teacher = userRepository.findById(code.teacherUserId).orElse(null)
        val school = code.schoolId?.let { schoolRepository.findById(it).orElse(null) }
        val clazz = classRepository.findAllByTeacherUserIdAndIsActiveTrueOrderByNameAsc(code.teacherUserId).firstOrNull()
        val requestedSchool = request.schoolId?.trim()?.takeIf { it.isNotEmpty() }?.let { UUID.fromString(it) }
        if (requestedSchool != null && code.schoolId != null && requestedSchool != code.schoolId) {
            return CtcValidationPayload(
                isValid = false,
                teacherName = teacher?.name,
                message = "This class code belongs to a different school",
                ctcType = CtcType.TEACHER,
            )
        }
        if (code.frozen) {
            return CtcValidationPayload(
                isValid = false,
                teacherName = teacher?.name,
                message = "This class code has been frozen by the teacher",
                ctcType = CtcType.TEACHER,
                teacherCode = code.code,
                schoolId = school?.id?.toString(),
                schoolName = school?.name,
                grade = clazz?.gradeLevel,
                className = clazz?.name,
            )
        }
        return CtcValidationPayload(
            isValid = true,
            teacherName = teacher?.name,
            message = "Valid class code",
            ctcType = CtcType.TEACHER,
            teacherCode = code.code,
            schoolId = school?.id?.toString(),
            schoolName = school?.name,
            grade = clazz?.gradeLevel,
            className = clazz?.name,
        )
    }

    @Transactional
    fun rotateCtc(current: CurrentUser): RotateCtcResponsePayload {
        val teacher = requireTeacher(current)
        val code = issueCode(teacher)
        return RotateCtcResponsePayload(
            success = true,
            message = "Class code rotated",
            teacherCode = code,
            user = userPayloadFactory.toPayload(teacher),
        )
    }

    @Transactional
    fun setCtcFrozen(current: CurrentUser, frozen: Boolean): AuthResponse {
        val teacher = requireTeacher(current)
        val code = teacherCodeRepository.findByTeacherUserIdAndActiveTrue(teacher.id)
            ?: throw notFound("Class code not found")
        code.frozen = frozen
        teacherCodeRepository.save(code)
        return authService.accountResponse(teacher, if (frozen) "Class code frozen" else "Class code unfrozen")
    }

    // ----------------------------------------------------- approval decisions

    @Transactional
    fun decideUser(current: CurrentUser, targetIdRaw: String, status: AccountStatus, message: String): AuthResponse {
        val actor = requireUser(current)
        val target = findUser(targetIdRaw)
        when (target.role) {
            Role.STUDENT -> authorizeStudentApprover(actor, target)
            Role.TEACHER -> {
                requireIctAdmin(actor)
                requireSameSchool(actor, target)
            }
            else -> throw invalidArgument("Only students and teachers can be approved or rejected")
        }
        applyStatus(target, status)
        return authService.accountResponse(target, message)
    }

    @Transactional
    fun freezeTeacher(current: CurrentUser, targetIdRaw: String, frozen: Boolean): AuthResponse {
        val actor = requireIctAdmin(requireUser(current))
        val target = findUser(targetIdRaw)
        if (target.role != Role.TEACHER) throw invalidArgument("Target is not a teacher")
        requireSameSchool(actor, target)
        applyStatus(target, if (frozen) AccountStatus.FROZEN else AccountStatus.VERIFIED)
        return authService.accountResponse(target, if (frozen) "Teacher frozen" else "Teacher unfrozen")
    }

    @Transactional
    fun transferTeacher(current: CurrentUser, targetIdRaw: String, request: TeacherTransferRequest): AuthResponse {
        val actor = requireIctAdmin(requireUser(current))
        val target = findUser(targetIdRaw)
        if (target.role != Role.TEACHER) throw invalidArgument("Target is not a teacher")
        requireSameSchool(actor, target)
        val school = resolveTargetSchool(request)
        target.schoolId = school?.id
        userRepository.save(target)
        val code = issueCode(target)
        applyStatus(target, AccountStatus.VERIFIED)
        return authService.accountResponse(target, "Teacher transferred to " + (school?.name ?: "the new school"))
    }

    @Transactional
    fun updateTeacher(current: CurrentUser, targetIdRaw: String, request: TeacherInfoUpdateRequest): AuthResponse {
        val actor = requireUser(current)
        val target = findUser(targetIdRaw)
        if (target.role != Role.TEACHER) throw invalidArgument("Target is not a teacher")
        if (actor.id != target.id) {
            requireIctAdmin(actor)
            requireSameSchool(actor, target)
        }
        request.name?.takeIf { it.isNotBlank() }?.let { target.name = it.trim() }
        request.phoneNumber?.takeIf { it.isNotBlank() }?.let { phone ->
            val trimmed = phone.trim()
            if (trimmed != target.phoneNumber) {
                if (userRepository.existsByPhoneNumber(trimmed)) throw conflict("Another account already uses this phone number")
                target.phoneNumber = trimmed
            }
        }
        userRepository.save(target)

        val settings = teacherSettingsRepository.findByTeacherId(target.id)
            ?: TeacherSettingsEntity().apply { teacherId = target.id }
        request.subjects?.let { settings.subjectsTaught = codec.toJson(it.filter { s -> s.isNotBlank() }) }
        request.tscNumber?.let { settings.tscNumber = it.trim().takeIf { value -> value.isNotEmpty() } }
        teacherSettingsRepository.save(settings)

        val className = request.className?.trim()?.takeIf { it.isNotEmpty() }
        val grades = request.gradeLevels?.map { it.trim() }?.filter { it.isNotEmpty() }
        if (className != null || !grades.isNullOrEmpty()) {
            val clazz = classRepository.findAllByTeacherUserIdAndIsActiveTrueOrderByNameAsc(target.id).firstOrNull()
                ?: TeacherClassEntity().apply {
                    teacherUserId = target.id
                    schoolId = target.schoolId
                    name = className ?: "Class"
                    gradeLevel = grades?.firstOrNull().orEmpty()
                    subject = codec.parseList(settings.subjectsTaught)?.firstOrNull() ?: "General"
                    isActive = true
                }
            className?.let { clazz.name = it }
            grades?.firstOrNull()?.let { clazz.gradeLevel = it }
            classRepository.save(clazz)
        }
        return authService.accountResponse(target, "Teacher profile updated")
    }

    // ------------------------------------------------------------- internals

    private fun issueCode(teacher: UserEntity): String {
        teacherCodeRepository.findByTeacherUserIdAndActiveTrue(teacher.id)?.let { existing ->
            existing.active = false
            teacherCodeRepository.save(existing)
        }
        val code = teacherCodeGenerator.generate()
        teacherCodeRepository.save(
            TeacherCodeEntity().apply {
                this.code = code
                teacherUserId = teacher.id
                schoolId = teacher.schoolId
                active = true
                frozen = false
            }
        )
        return code
    }

    private fun applyStatus(user: UserEntity, status: AccountStatus) {
        user.verificationStatus = status
        when (status) {
            AccountStatus.VERIFIED -> {
                user.isVerified = true
                user.isActive = true
            }
            AccountStatus.PENDING_VERIFICATION -> {
                user.isVerified = false
                user.isActive = true
            }
            AccountStatus.REJECTED, AccountStatus.FROZEN -> {
                user.isVerified = false
                user.isActive = false
            }
        }
        userRepository.save(user)
    }

    private fun authorizeStudentApprover(actor: UserEntity, student: UserEntity) {
        if (actor.role == Role.ADMIN) return
        if (actor.role == Role.TEACHER && (actor.subRole == SubRole.GRADE_COORDINATOR || actor.subRole == SubRole.ICT_ADMIN)) return
        if (actor.role == Role.TEACHER) {
            if (student.joinedTeacherId == actor.id) return
            val studentClasses = membershipRepository.findAllByStudentId(student.id).map { it.classId }.toSet()
            val teacherClasses = classRepository.findAllByTeacherUserIdAndIsActiveTrueOrderByNameAsc(actor.id).map { it.id }.toSet()
            if (studentClasses.intersect(teacherClasses).isNotEmpty()) return
        }
        throw ApiException(ApiErrorCode.FORBIDDEN, "Not your learner")
    }

    private fun requireIctAdmin(actor: UserEntity): UserEntity {
        val allowed = actor.role == Role.ADMIN || (actor.role == Role.TEACHER && actor.subRole == SubRole.ICT_ADMIN)
        if (!allowed) throw ApiException(ApiErrorCode.FORBIDDEN, "ICT admin access required")
        return actor
    }

    private fun requireSameSchool(actor: UserEntity, target: UserEntity) {
        if (actor.role == Role.ADMIN) return
        if (target.schoolId == null || actor.schoolId == null || target.schoolId != actor.schoolId) {
            throw ApiException(ApiErrorCode.FORBIDDEN, "Not your school")
        }
    }

    private fun requireTeacher(current: CurrentUser): UserEntity {
        val user = requireUser(current)
        if (user.role != Role.TEACHER) throw ApiException(ApiErrorCode.FORBIDDEN, "Teacher access only")
        return user
    }

    private fun requireUser(current: CurrentUser): UserEntity =
        userRepository.findById(current.userId).orElse(null) ?: throw notFound("User not found")

    private fun findUser(raw: String): UserEntity {
        val id = runCatching { UUID.fromString(raw.trim()) }.getOrNull()
            ?: throw invalidArgument("teacherId is not a valid identifier")
        return userRepository.findById(id).orElse(null) ?: throw notFound("User not found")
    }

    private fun resolveSchool(requestedId: String?, requestedName: String): SchoolEntity? {
        if (!requestedId.isNullOrBlank()) {
            val id = runCatching { UUID.fromString(requestedId.trim()) }.getOrNull()
                ?: throw invalidArgument("schoolId is not a valid identifier")
            return schoolRepository.findById(id).orElseThrow { notFound("School not found") }
        }
        if (requestedName.isNotBlank()) {
            val name = requestedName.trim()
            return schoolRepository.findByNameIgnoreCase(name) ?: schoolRepository.save(
                SchoolEntity().apply {
                    this.name = name
                    isActive = true
                }
            )
        }
        return null
    }

    private fun resolveTargetSchool(request: TeacherTransferRequest): SchoolEntity? {
        if (request.targetSchoolId.isNotBlank()) {
            val id = runCatching { UUID.fromString(request.targetSchoolId.trim()) }.getOrNull()
                ?: throw invalidArgument("targetSchoolId is not a valid identifier")
            return schoolRepository.findById(id).orElseThrow { notFound("Target school not found") }
        }
        if (request.targetSchoolName.isNotBlank()) {
            val name = request.targetSchoolName.trim()
            return schoolRepository.findByNameIgnoreCase(name) ?: schoolRepository.save(
                SchoolEntity().apply {
                    this.name = name
                    isActive = true
                }
            )
        }
        throw invalidArgument("A target school is required")
    }

    private fun shareText(code: String): String =
        "Join my class on BrainBox with class code " + code + ". Open BrainBox, sign up as a student and enter the code."
}
