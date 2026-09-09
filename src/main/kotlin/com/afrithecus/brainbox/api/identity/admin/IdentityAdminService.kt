package com.afrithecus.brainbox.api.identity.admin

import com.afrithecus.brainbox.api.common.error.ApiException
import com.afrithecus.brainbox.api.common.error.ApiErrorCode
import com.afrithecus.brainbox.api.common.error.conflict
import com.afrithecus.brainbox.api.common.error.invalidArgument
import com.afrithecus.brainbox.api.common.error.notFound
import com.afrithecus.brainbox.api.identity.entity.SchoolEntity
import com.afrithecus.brainbox.api.identity.entity.TeacherCodeEntity
import com.afrithecus.brainbox.api.identity.entity.TeacherProfileEntity
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.model.Role
import com.afrithecus.brainbox.api.identity.model.SubscriptionStatus
import com.afrithecus.brainbox.api.identity.model.SubscriptionTier
import com.afrithecus.brainbox.api.identity.repository.SchoolRepository
import com.afrithecus.brainbox.api.identity.repository.TeacherCodeRepository
import com.afrithecus.brainbox.api.identity.repository.TeacherProfileRepository
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import com.afrithecus.brainbox.api.identity.web.AdminUserListResponse
import com.afrithecus.brainbox.api.identity.web.CreateTeacherRequest
import com.afrithecus.brainbox.api.identity.web.SchoolPayload
import com.afrithecus.brainbox.api.identity.web.SubscriptionUpdateRequest
import com.afrithecus.brainbox.api.identity.web.TeacherPayload
import com.afrithecus.brainbox.api.identity.web.UpdateSchoolRequest
import com.afrithecus.brainbox.api.identity.web.UpdateUserRequest
import com.afrithecus.brainbox.api.auth.web.UserPayload
import com.afrithecus.brainbox.api.identity.web.UserPayloadFactory
import com.afrithecus.brainbox.api.identity.web.ResetPasswordResponse
import com.afrithecus.brainbox.api.subscription.SubscriptionService
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.SecureRandom
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * Identity administration (doc 01): user management (§2.2), parent-child links
 * (§6.1), teacher creation with server-issued CTC (§7.2), approvals (§8.2) and
 * school management (§9). Admin-only endpoints; scoping guards enforced at the
 * controller layer with hasRole('ADMIN').
 */
@Service
class IdentityAdminService(
    private val userRepository: UserRepository,
    private val schoolRepository: SchoolRepository,
    private val teacherCodeRepository: TeacherCodeRepository,
    private val teacherProfileRepository: TeacherProfileRepository,
    private val subscriptionService: SubscriptionService,
    private val passwordEncoder: PasswordEncoder,
    private val userPayloadFactory: UserPayloadFactory,
    private val clock: Clock,
) {

    // ------------------------------------------------------- user management

    @Transactional(readOnly = true)
    fun listUsers(
        roleRaw: String?,
        schoolIdRaw: String?,
        status: String?,
        search: String?,
        page: Int,
        limit: Int,
    ): AdminUserListResponse {
        val role = roleRaw?.let { parseRole(it) }
        val schoolId = schoolIdRaw?.let { parseUuid(it, "schoolId") }
        val active = when (status?.uppercase()) {
            null, "", "ALL" -> null
            "ACTIVE" -> true
            "INACTIVE" -> false
            else -> throw invalidArgument("status must be ACTIVE, INACTIVE or ALL")
        }
        val pageIndex = page.coerceAtLeast(0)
        val pageSize = limit.coerceIn(1, 100)
        val result = userRepository.search(
            role = role,
            schoolId = schoolId,
            active = active,
            q = search?.takeIf { it.isNotBlank() },
            pageable = PageRequest.of(pageIndex, pageSize, Sort.by(Sort.Direction.DESC, "createdAt")),
        )
        return AdminUserListResponse(
            users = result.content.map { userPayloadFactory.toPayload(it) },
            total = result.totalElements.toInt(),
            page = pageIndex,
            totalPages = result.totalPages,
        )
    }

    @Transactional(readOnly = true)
    fun getUser(userIdRaw: String): UserPayload = userPayloadFactory.toPayload(findUser(userIdRaw))

    @Transactional
    fun updateUser(userIdRaw: String, request: UpdateUserRequest): UserPayload {
        val user = findUser(userIdRaw)
        request.name?.takeIf { it.isNotBlank() }?.let { user.name = it.trim() }
        request.gradeLevel?.let { user.gradeLevel = it.takeIf { g -> g.isNotBlank() } }
        request.isActive?.let { user.isActive = it }
        userRepository.save(user)
        return userPayloadFactory.toPayload(user)
    }

    @Transactional
    fun deactivateUser(userIdRaw: String) {
        val user = findUser(userIdRaw)
        user.isActive = false
        userRepository.save(user)
    }

    @Transactional
    fun updateSubscription(userIdRaw: String, request: SubscriptionUpdateRequest) {
        val user = findUser(userIdRaw)
        val tier = runCatching { SubscriptionTier.valueOf(request.tier.trim().uppercase()) }
            .getOrNull() ?: throw invalidArgument("tier must be BASE, EXPLORER or PRO")
        val row = subscriptionService.ensure(user.id)
        row.tier = tier
        when (tier) {
            SubscriptionTier.BASE -> {
                row.status = SubscriptionStatus.NONE
                row.expiryDate = null
            }
            SubscriptionTier.EXPLORER, SubscriptionTier.PRO -> {
                val now = clock.instant()
                row.expiryDate = request.expiryDate?.let(Instant::ofEpochMilli)
                    ?: row.expiryDate?.takeIf { it.isAfter(now) }
                    ?: now.plus(java.time.Duration.ofDays(30))
                row.status = if (row.expiryDate!!.isAfter(now)) SubscriptionStatus.ACTIVE else SubscriptionStatus.EXPIRED
            }
        }
        request.totalPaid?.let { row.totalPaid = it }
        subscriptionService.view(user.id) // persists any derived status transition
    }

    @Transactional
    fun resetPassword(userIdRaw: String): ResetPasswordResponse {
        val user = findUser(userIdRaw)
        val raw = randomPassword()
        user.passwordHash = passwordEncoder.encode(raw) ?: throw IllegalStateException("Password encoding failed")
        userRepository.save(user)
        return ResetPasswordResponse(password = raw)
    }

    // --------------------------------------------------- parent-child linking

    @Transactional
    fun linkParent(parentIdRaw: String, childIdRaw: String) {
        if (parentIdRaw == childIdRaw) throw invalidArgument("parent and child must differ")
        val parentId = parseUuid(parentIdRaw, "parentId")
        val childId = parseUuid(childIdRaw, "childId")
        val parent = userRepository.findById(parentId).orElseThrow { notFound("Parent not found") }
        val child = userRepository.findById(childId).orElseThrow { notFound("Child not found") }
        if (parent.role != Role.PARENT) throw invalidArgument("parentId must reference a PARENT user")
        if (child.role != Role.STUDENT) throw invalidArgument("childId must reference a STUDENT user")
        child.parentUserId?.let { existing ->
            if (existing != parentId) throw conflict("Child is already linked to another parent")
        }
        child.parentUserId = parentId
        userRepository.save(child)
    }

    // --------------------------------------------------- verification (doc 01 §8)

    @Transactional
    fun setVerified(userIdRaw: String, verified: Boolean) {
        val user = findUser(userIdRaw)
        user.isVerified = verified
        userRepository.save(user)
    }

    // ----------------------------------------------------- teacher management

    @Transactional
    fun createTeacher(schoolIdRaw: String, request: CreateTeacherRequest): TeacherPayload {
        val school = findSchool(schoolIdRaw)
        val phone = request.phoneNumber.trim()
        val email = request.email.trim().lowercase()
        if (userRepository.existsByPhoneNumber(phone)) {
            throw conflict("A user with this phone number already exists")
        }
        if (userRepository.existsByEmail(email)) {
            throw conflict("A user with this email already exists")
        }

        val teacher = UserEntity().apply {
            phoneNumber = phone
            this.email = email
            name = request.name.trim()
            passwordHash = passwordEncoder.encode(randomPassword())
                ?: throw IllegalStateException("Password encoding failed")
            role = Role.TEACHER
            schoolId = school.id
            isActive = true
            isVerified = true
        }
        userRepository.save(teacher)

        teacherProfileRepository.save(
            TeacherProfileEntity().apply {
                userId = teacher.id
                this.schoolId = school.id
                subject = request.subject?.trim()?.takeIf { it.isNotEmpty() }
            }
        )

        val code = generateTeacherCode()
        teacherCodeRepository.save(
            TeacherCodeEntity().apply {
                this.code = code
                teacherUserId = teacher.id
                this.schoolId = school.id
                active = true
            }
        )
        return toTeacherPayload(teacher, code)
    }

    @Transactional(readOnly = true)
    fun listTeachers(schoolIdRaw: String): List<TeacherPayload> {
        val school = findSchool(schoolIdRaw)
        val teachers = userRepository.search(
            role = Role.TEACHER,
            schoolId = school.id,
            active = null,
            q = null,
            pageable = PageRequest.of(0, 10000, Sort.by("name")),
        ).content
        return teachers.map { teacher ->
            val code = teacherCodeRepository.findByTeacherUserIdAndActiveTrue(teacher.id)?.code
            toTeacherPayload(teacher, code)
        }
    }

    @Transactional
    fun removeTeacher(schoolIdRaw: String, teacherIdRaw: String) {
        val school = findSchool(schoolIdRaw)
        val teacherId = parseUuid(teacherIdRaw, "teacherId")
        val teacher = userRepository.findById(teacherId).orElseThrow { notFound("Teacher not found") }
        if (teacher.role != Role.TEACHER || teacher.schoolId != school.id) {
            throw ApiException(ApiErrorCode.NOT_FOUND, "Teacher not found in this school")
        }
        teacherCodeRepository.findByTeacherUserIdAndActiveTrue(teacher.id)?.let { code ->
            code.active = false
            teacherCodeRepository.save(code)
        }
        teacherProfileRepository.findByUserId(teacher.id)?.let { profile ->
            teacherProfileRepository.delete(profile)
        }
        teacher.schoolId = null
        userRepository.save(teacher)
    }

    // ----------------------------------------------------------- school ops

    @Transactional
    fun updateSchool(schoolIdRaw: String, request: UpdateSchoolRequest): SchoolPayload {
        val school = findSchool(schoolIdRaw)
        request.name?.takeIf { it.isNotBlank() }?.let { school.name = it.trim() }
        request.county?.let { school.county = it.takeIf { c -> c.isNotBlank() } }
        request.location?.let { school.location = it.takeIf { l -> l.isNotBlank() } }
        schoolRepository.save(school)
        return toSchoolPayload(school)
    }

    @Transactional
    fun deactivateSchool(schoolIdRaw: String) {
        val school = findSchool(schoolIdRaw)
        school.isActive = false
        schoolRepository.save(school)
    }

    // -------------------------------------------------------- public reads

    @Transactional(readOnly = true)
    fun searchSchools(query: String): List<SchoolPayload> =
        if (query.isBlank()) emptyList()
        else schoolRepository.findByNameContainingIgnoreCaseOrderByNameAsc(query.trim())
            .filter { it.isActive }.map(::toSchoolPayload)

    @Transactional(readOnly = true)
    fun allSchools(): List<SchoolPayload> =
        schoolRepository.findAllByOrderByNameAsc().filter { it.isActive }.map(::toSchoolPayload)

    @Transactional(readOnly = true)
    fun getSchool(schoolIdRaw: String): SchoolPayload = toSchoolPayload(findSchool(schoolIdRaw))

    // ------------------------------------------------------------- helpers

    private fun toSchoolPayload(school: SchoolEntity): SchoolPayload = SchoolPayload(
        id = school.id.toString(),
        name = school.name,
        county = school.county,
        location = school.location,
        logoUrl = school.logoUrl,
        rating = 0f,
        reviews = 0,
        placementRate = 0,
        rank = 0,
        studentCount = userRepository.countBySchoolIdAndRole(school.id, Role.STUDENT).toInt(),
        teacherCount = userRepository.countBySchoolIdAndRole(school.id, Role.TEACHER).toInt(),
        createdAt = school.createdAt.toEpochMilli(),
    )

    private fun toTeacherPayload(teacher: UserEntity, teacherCode: String?): TeacherPayload {
        val subject = teacherProfileRepository.findByUserId(teacher.id)?.subject
        return TeacherPayload(
            id = teacher.id.toString(),
            name = teacher.name,
            email = teacher.email,
            phoneNumber = teacher.phoneNumber,
            subject = subject,
            teacherCode = teacherCode,
            schoolId = teacher.schoolId?.toString(),
            isActive = teacher.isActive,
        )
    }

    private fun generateTeacherCode(): String {
        repeat(MAX_CODE_ATTEMPTS) {
            val candidate = buildString(CODE_LENGTH) {
                repeat(CODE_LENGTH) { append(CODE_ALPHABET[random.nextInt(CODE_ALPHABET.length)]) }
            }
            if (!teacherCodeRepository.existsByCode(candidate)) return candidate
        }
        throw ApiException(ApiErrorCode.CONFLICT, "Could not allocate a teacher code, retry")
    }

    private fun randomPassword(): String {
        val bytes = ByteArray(12)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }.take(12)
    }

    private fun findUser(raw: String): UserEntity =
        userRepository.findById(parseUuid(raw, "userId"))
            .orElseThrow { notFound("User not found") }

    private fun findSchool(raw: String): SchoolEntity {
        val school = schoolRepository.findById(parseUuid(raw, "schoolId"))
            .orElseThrow { notFound("School not found") }
        if (!school.isActive) throw notFound("School not found")
        return school
    }

    private fun parseUuid(raw: String, field: String): UUID =
        runCatching { UUID.fromString(raw) }.getOrNull()
            ?: throw invalidArgument(field + " is not a valid identifier")

    private fun parseRole(raw: String): Role {
        val role = runCatching { Role.valueOf(raw.trim().uppercase()) }.getOrNull()
            ?: throw invalidArgument("role must be one of STUDENT, TEACHER, PARENT, ADMIN")
        return role
    }

    private companion object {
        const val CODE_LENGTH = 6
        const val MAX_CODE_ATTEMPTS = 100
        const val CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val random = SecureRandom()
    }
}
