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
import com.afrithecus.brainbox.api.identity.AuditLogService
import com.afrithecus.brainbox.api.identity.SchoolConfigService
import com.afrithecus.brainbox.api.identity.TeacherCodeGenerator
import com.afrithecus.brainbox.api.identity.entity.SchoolEntity
import com.afrithecus.brainbox.api.identity.entity.TeacherCodeEntity
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.model.AccountStatus
import com.afrithecus.brainbox.api.identity.model.CurrentUser
import com.afrithecus.brainbox.api.identity.model.Role
import com.afrithecus.brainbox.api.identity.model.SubRole
import com.afrithecus.brainbox.api.identity.repository.SchoolRepository
import com.afrithecus.brainbox.api.identity.repository.SchoolSystemSettingsRepository
import com.afrithecus.brainbox.api.identity.repository.TeacherCodeRepository
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import com.afrithecus.brainbox.api.identity.web.UserPayloadFactory
import com.afrithecus.brainbox.api.security.AuthThrottle
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
    private val settingsRepository: SchoolSystemSettingsRepository,
    private val teacherCodeRepository: TeacherCodeRepository,
    private val teacherSettingsRepository: TeacherSettingsRepository,
    private val classRepository: TeacherClassRepository,
    private val membershipRepository: ClassMembershipRepository,
    private val subscriptionService: SubscriptionService,
    private val passwordEncoder: PasswordEncoder,
    private val userPayloadFactory: UserPayloadFactory,
    private val teacherCodeGenerator: TeacherCodeGenerator,
    private val authService: AuthService,
    private val emailVerificationService: EmailVerificationService,
    private val schoolConfigService: SchoolConfigService,
    private val auditLogService: AuditLogService,
    private val authThrottle: AuthThrottle,
    private val codec: QuestionCodec,
) {

    // ----------------------------------------------------------- teacher signup

    @Transactional
    fun teacherSignup(request: TeacherSignupRequest, deviceId: String?): TeacherSignupResponse {
        // F9/N6: staff keep a password (8+) and a canonical phone format, the same normaliser
        // the learner signup uses so the unique-phone index cannot be defeated by formatting.
        val phone = PhoneNumbers.normalize(request.phoneNumber)
            ?: throw invalidArgument("phoneNumber is not a valid Kenyan phone number")
        CredentialRules.validate(CredentialRules.KIND_PASSWORD, request.password)
            ?.let { throw invalidArgument(it) }
        val email = Emails.normalize(request.email)
            ?: throw invalidArgument("Enter a valid email address")
        if (userRepository.existsByPhoneNumber(phone)) {
            throw conflict("An account with this phone number already exists")
        }
        if (userRepository.existsByEmailIgnoreCase(email)) {
            throw conflict("An account with this email address already exists")
        }
        // The school is picked from the directory; its grades/classes come from its own config.
        val school = requireSchool(request.schoolId)
        if (settingsRepository.findById(school.id).orElse(null)?.registrationOpen == false) {
            throw ApiException(
                ApiErrorCode.FORBIDDEN,
                "Registration is currently closed for " + school.name + ". Ask the school's ICT admin to open it.",
            )
        }
        val subRole = parseSubRole(request.subRole)
        val grades = request.grades.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        val className = request.className.trim()
        val catalogue = schoolConfigService.configuredClasses(school.id)
            .map { it.gradeLevel.trim().lowercase() to it.name.trim().lowercase() }
        validateSelection(subRole, grades, className, catalogue)
        // The first ICT admin bootstraps the school; later ones are approved by an existing admin.
        if (subRole == SubRole.ICT_ADMIN && userRepository.existsBySchoolIdAndSubRoleAndIsActiveTrue(school.id, SubRole.ICT_ADMIN)) {
            throw ApiException(
                ApiErrorCode.FORBIDDEN,
                "This school already has an ICT admin. Ask them to approve you, or contact the platform team.",
            )
        }
        val teacher = UserEntity().apply {
            phoneNumber = phone
            this.email = email
            name = request.name.trim()
            passwordHash = passwordEncoder.encode(request.password)
                ?: throw IllegalStateException("Password encoding failed")
            role = Role.TEACHER
            this.subRole = subRole
            credentialKind = CredentialRules.KIND_PASSWORD
            schoolId = school.id
            gradeLevel = grades.firstOrNull()
            isActive = true
            isVerified = false
            verificationStatus = AccountStatus.PENDING_VERIFICATION
            emailVerificationRequired = emailVerificationService.required
        }
        userRepository.save(teacher)
        subscriptionService.ensure(teacher.id)

        teacherSettingsRepository.save(
            TeacherSettingsEntity().apply {
                teacherId = teacher.id
                subjectsTaught = codec.toJson(request.subjects.orEmpty().filter { it.isNotBlank() })
                gradesTaught = codec.toJson(grades)
                primaryClassName = className.takeIf { it.isNotEmpty() }
                tscNumber = request.tscNumber?.trim()?.takeIf { it.isNotEmpty() }
            }
        )
        if (subRole == SubRole.CTEACHER && className.isNotEmpty()) {
            classRepository.save(
                TeacherClassEntity().apply {
                    teacherUserId = teacher.id
                    this.schoolId = teacher.schoolId
                    name = className
                    gradeLevel = grades.first()
                    subject = request.subjects?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() } ?: "General"
                    isActive = true
                }
            )
        }
        val code = issueCode(teacher)
        // The account is unusable until the address is confirmed. A delivery failure rolls the whole
        // signup back rather than leaving an account whose code can never arrive.
        emailVerificationService.sendSignupCode(teacher)
        val verificationRequired = emailVerificationService.required
        val auth = if (verificationRequired) {
            null
        } else {
            authService.issueAuthResponse(teacher, deviceId, includeTokens = true, message = "Teacher account created")
        }
        return TeacherSignupResponse(
            success = true,
            message = if (verificationRequired) {
                "Account created. Enter the 6-digit code we sent to " + Emails.mask(email) + "."
            } else {
                auth!!.message
            },
            sessionToken = auth?.sessionToken,
            user = auth?.user ?: userPayloadFactory.toPayload(teacher),
            ctc = code,
            ctcShareText = shareText(code),
            isNewUser = true,
            emailVerificationRequired = verificationRequired,
            maskedEmail = if (verificationRequired) Emails.mask(email) else null,
        )
    }

    // ------------------------------------------------------------------- CTC

    @Transactional(readOnly = true)
    fun validateCtc(request: ValidateCtcRequest): CtcValidationPayload {
        val raw = request.ctc.trim().uppercase()
        authThrottle.check(CTC_THROTTLE_PREFIX + raw)
        if (raw.isEmpty()) return CtcValidationPayload(isValid = false, message = "Enter a class code")
        val code = teacherCodeRepository.findByCodeAndActiveTrue(raw)
            ?: run {
                authThrottle.recordFailure(CTC_THROTTLE_PREFIX + raw)
                return CtcValidationPayload(isValid = false, message = "Unknown or inactive class code")
            }
        val teacher = userRepository.findById(code.teacherUserId).orElse(null)
        val school = code.schoolId?.let { schoolRepository.findById(it).orElse(null) }
        val clazz = classRepository.findAllByTeacherUserIdAndIsActiveTrueOrderByNameAsc(code.teacherUserId).firstOrNull()
        // A pending teacher holds a code so they can share it after approval, but it must not work
        // before an ICT admin has approved the account (gaps.md item 1D).
        if (teacher != null && teacher.verificationStatus != AccountStatus.VERIFIED) {
            return CtcValidationPayload(
                isValid = false,
                teacherName = teacher.name,
                message = "This class code is not active yet: the teacher's account is still awaiting approval.",
                ctcType = CtcType.TEACHER,
                teacherCode = code.code,
                schoolId = code.schoolId?.toString(),
                schoolName = school?.name,
                grade = clazz?.gradeLevel,
                className = clazz?.name,
            )
        }
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
            authThrottle.recordFailure(CTC_THROTTLE_PREFIX + raw)
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
        authThrottle.clear(CTC_THROTTLE_PREFIX + raw)
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
        // F9: every approval decision is audited, students included (a decision that changes
        // access must have evidence), and a rejected/frozen account is signed out everywhere.
        (target.schoolId ?: actor.schoolId)?.let { auditLogService.record(it, actor, message + ": " + target.name) }
        return authService.accountResponse(target, message)
    }

    @Transactional
    fun freezeTeacher(current: CurrentUser, targetIdRaw: String, frozen: Boolean): AuthResponse {
        val actor = requireIctAdmin(requireUser(current))
        val target = findUser(targetIdRaw)
        if (target.role != Role.TEACHER) throw invalidArgument("Target is not a teacher")
        requireSameSchool(actor, target)
        applyStatus(target, if (frozen) AccountStatus.FROZEN else AccountStatus.VERIFIED)
        target.schoolId?.let {
            auditLogService.record(it, actor, (if (frozen) "Froze teacher " else "Unfroze teacher ") + target.name)
        }
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
        school?.id?.let {
            auditLogService.record(it, actor, "Transferred teacher " + target.name + " to " + school.name)
        }
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
        } else {
            // N3: a teacher self-editing through the admin teacher route must not bypass the
            // school-admin approval gate. Name/phone/TSC stay self-service; class, grades and
            // subjects are governed and belong to the change-request flow.
            val touchesGovernedField =
                request.subjects != null || request.className != null || request.gradeLevels != null
            if (touchesGovernedField) {
                throw conflict(
                    "Class, grades and subjects need your school admin's approval. Request the change from teacher settings.",
                )
            }
        }
        request.name?.takeIf { it.isNotBlank() }?.let { target.name = it.trim() }
        request.phoneNumber?.takeIf { it.isNotBlank() }?.let { phone ->
            val normalised = PhoneNumbers.normalize(phone)
                ?: throw invalidArgument("phoneNumber is not a valid Kenyan phone number")
            if (normalised != target.phoneNumber) {
                if (userRepository.existsByPhoneNumber(normalised)) throw conflict("Another account already uses this phone number")
                target.phoneNumber = normalised
                // A verified phone that changed is no longer verified (docs/phone_verification.md).
                target.phoneVerifiedAt = null
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
        if (actor.id != target.id) {
            target.schoolId?.let { auditLogService.record(it, actor, "Updated teacher " + target.name) }
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
                // F9: the status change is not cosmetic — an access token issued before it
                // must stop working now, not after its 24h TTL.
                authService.revokeAllUserSessions(user.id)
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

    /**
     * Staff signup picks a school from the directory; it never creates one from free text.
     * A school that does not exist yet is added by the platform team, then its ICT admin
     * configures the grades and classes (gaps.md items 1C/E).
     */
    private fun requireSchool(rawId: String?): SchoolEntity {
        if (rawId.isNullOrBlank()) {
            throw invalidArgument("Select your school from the list. If your school is missing, ask the platform team to add it.")
        }
        val id = runCatching { UUID.fromString(rawId.trim()) }.getOrNull()
            ?: throw invalidArgument("schoolId is not a valid identifier")
        val school = schoolRepository.findById(id).orElse(null) ?: throw notFound("School not found")
        if (!school.isActive) throw invalidArgument("That school is not accepting sign-ups.")
        return school
    }

    private fun parseSubRole(raw: String?): SubRole? {
        val value = raw?.trim()?.uppercase().orEmpty()
        if (value.isEmpty() || value == "TEACHER" || value == "SUBJECT_TEACHER") return null
        return runCatching { SubRole.valueOf(value) }.getOrNull()
            ?: throw invalidArgument("subRole must be TEACHER, CTEACHER, GRADE_COORDINATOR or ICT_ADMIN")
    }

    /**
     * The server, not the client, decides which details a role needs, and a class teacher's
     * grade/class must exist in the school's own catalogue (gaps.md item 1B/C).
     */
    private fun validateSelection(
        subRole: SubRole?,
        grades: List<String>,
        className: String,
        catalogue: List<Pair<String, String>>,
    ) {
        when (subRole) {
            SubRole.ICT_ADMIN -> {
                if (grades.isNotEmpty() || className.isNotEmpty()) {
                    throw invalidArgument("An ICT admin account does not take a grade or class.")
                }
            }
            SubRole.CTEACHER -> {
                if (grades.size != 1) throw invalidArgument("Select the one grade your class belongs to.")
                if (catalogue.isEmpty()) throw invalidArgument(SCHOOL_NOT_CONFIGURED)
                if (className.isEmpty()) throw invalidArgument("Select your class from your school's list.")
                val grade = grades.first().lowercase()
                val name = className.lowercase()
                if (catalogue.none { it.first == grade && it.second == name }) {
                    throw invalidArgument("That class is not on your school's list. Pick one of the listed classes.")
                }
            }
            else -> {
                if (grades.isEmpty()) throw invalidArgument("Select at least one grade you teach.")
                if (className.isNotEmpty()) throw invalidArgument("Only a class teacher picks a class.")
                if (catalogue.isNotEmpty() && grades.any { g -> catalogue.none { it.first == g.lowercase() } }) {
                    throw invalidArgument("One of the grades you picked is not on your school's list.")
                }
            }
        }
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

    private companion object {
        const val CTC_THROTTLE_PREFIX = "ctc:"
        const val SCHOOL_NOT_CONFIGURED =
            "Your school has not set up its grades and classes yet. Ask the ICT admin to configure them, then sign up."
    }
}
