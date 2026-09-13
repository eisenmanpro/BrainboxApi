package com.afrithecus.brainbox.api.auth

import com.afrithecus.brainbox.api.auth.web.AuthResponse
import com.afrithecus.brainbox.api.auth.web.LoginRequest
import com.afrithecus.brainbox.api.auth.web.RefreshRequest
import com.afrithecus.brainbox.api.auth.web.RefreshResponse
import com.afrithecus.brainbox.api.auth.web.SignupRequest
import com.afrithecus.brainbox.api.auth.web.SubscriptionPayload
import com.afrithecus.brainbox.api.common.error.ApiErrorCode
import com.afrithecus.brainbox.api.common.error.ApiException
import com.afrithecus.brainbox.api.common.error.conflict
import com.afrithecus.brainbox.api.common.error.invalidArgument
import com.afrithecus.brainbox.api.common.error.notFound
import com.afrithecus.brainbox.api.identity.entity.RefreshTokenEntity
import com.afrithecus.brainbox.api.identity.entity.SchoolEntity
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.entity.UserSessionEntity
import com.afrithecus.brainbox.api.identity.model.AccountStatus
import com.afrithecus.brainbox.api.identity.model.CurrentUser
import com.afrithecus.brainbox.api.identity.model.Role
import com.afrithecus.brainbox.api.identity.model.SessionRole
import com.afrithecus.brainbox.api.identity.model.SubscriptionStatus
import com.afrithecus.brainbox.api.identity.model.SubscriptionTier
import com.afrithecus.brainbox.api.identity.repository.RefreshTokenRepository
import com.afrithecus.brainbox.api.identity.repository.SchoolRepository
import com.afrithecus.brainbox.api.identity.repository.TeacherCodeRepository
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import com.afrithecus.brainbox.api.identity.repository.UserSessionRepository
import com.afrithecus.brainbox.api.identity.web.UserPayloadFactory
import com.afrithecus.brainbox.api.security.JwtTokenService
import com.afrithecus.brainbox.api.security.TokenHash
import com.afrithecus.brainbox.api.subscription.Entitlements
import com.afrithecus.brainbox.api.subscription.SubscriptionService
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.SecureRandom
import java.time.Clock
import java.util.UUID

/**
 * Authentication flows (doc 01 §1): signup, login, refresh rotation with reuse
 * detection, logout, and /auth/me. Multi-session policy (doc 01 §5): at most 3
 * student sessions per device, single session per device for teachers/parents.
 */
@Service
class AuthService(
    private val userRepository: UserRepository,
    private val schoolRepository: SchoolRepository,
    private val sessionRepository: UserSessionRepository,
    private val refreshRepository: RefreshTokenRepository,
    private val teacherCodeRepository: TeacherCodeRepository,
    private val subscriptionService: SubscriptionService,
    private val passwordEncoder: PasswordEncoder,
    private val jwtTokenService: JwtTokenService,
    private val userPayloadFactory: UserPayloadFactory,
    private val clock: Clock,
) {

    // ------------------------------------------------------------------ auth

    @Transactional
    fun signup(request: SignupRequest, deviceId: String?): AuthResponse {
        val role = parseSignupRole(request.role)
        val phone = request.phoneNumber.trim()

        if (userRepository.existsByPhoneNumber(phone)) {
            throw conflict("An account with this phone number already exists")
        }

        var joinedTeacher: UUID? = null
        var referredCode: String? = null
        var codeSchool: UUID? = null
        if (!request.teacherCode.isNullOrBlank()) {
            val code = teacherCodeRepository.findByCodeAndActiveTrue(request.teacherCode.trim().uppercase())
                ?: throw invalidArgument("Unknown or inactive teacher code")
            joinedTeacher = code.teacherUserId
            referredCode = code.code
            codeSchool = code.schoolId
        }

        val resolvedSchoolId = resolveSchool(
            requestedId = request.schoolId?.takeIf { it.isNotBlank() },
            requestedName = request.schoolName?.takeIf { it.isNotBlank() },
            teacherSchoolId = codeSchool,
        )?.id

        val user = UserEntity().apply {
            this.phoneNumber = phone
            name = request.name.trim()
            passwordHash = passwordEncoder.encode(request.password)
                ?: throw IllegalStateException("Password encoding failed")
            this.role = role
            this.schoolId = resolvedSchoolId
            studentAdmissionNumber = if (role == Role.STUDENT) generateAdmissionNumber() else null
            this.joinedTeacherId = joinedTeacher
            referredByTeacherCode = referredCode
            isActive = true
            isVerified = role == Role.PARENT
            verificationStatus = if (role == Role.PARENT) AccountStatus.VERIFIED else AccountStatus.PENDING_VERIFICATION
        }
        userRepository.save(user)

        subscriptionService.ensure(user.id)
        return issueAuthResponse(user, deviceId, includeTokens = true, message = "Account created")
    }

    @Transactional
    fun login(request: LoginRequest, deviceId: String?): AuthResponse {
        val user = resolveByIdentifier(request.identifier.trim())
            ?: throw unauthorized("Invalid identifier or password")

        if (!user.isActive) {
            throw unauthorized("Invalid identifier or password")
        }
        if (!passwordEncoder.matches(request.password, user.passwordHash)) {
            throw unauthorized("Invalid identifier or password")
        }

        user.lastLogin = clock.instant()
        userRepository.save(user)
        return issueAuthResponse(user, deviceId, includeTokens = true, message = "Login successful")
    }

    @Transactional
    fun refresh(request: RefreshRequest): RefreshResponse {
        val now = clock.instant()
        val stored = refreshRepository.findByTokenHash(TokenHash.sha256Hex(request.refreshToken))
            ?: throw unauthorized("Invalid refresh token")

        if (stored.revoked) {
            // Rotation family reuse = possible token theft: revoke the whole family.
            val family = refreshRepository.findByFamilyOrderByCreatedAtDesc(stored.family)
            val newerActive = family.any { !it.revoked && it.createdAt.isAfter(stored.createdAt) }
            if (newerActive) {
                family.forEach { it.revoked = true }
                refreshRepository.saveAll(family)
                sessionRepository.findAllByUserIdAndIsActiveTrue(stored.userId).forEach {
                    it.isActive = false
                    sessionRepository.save(it)
                }
            }
            throw unauthorized("Invalid refresh token")
        }
        if (!stored.expiresAt.isAfter(now)) {
            stored.revoked = true
            refreshRepository.save(stored)
            throw unauthorized("Invalid refresh token")
        }

        val user = userRepository.findById(stored.userId).orElse(null)
            ?: throw unauthorized("Invalid refresh token")
        if (!user.isActive) {
            throw unauthorized("Invalid refresh token")
        }
        stored.sessionId?.let { sessionId ->
            val session = sessionRepository.findByIdAndIsActiveTrue(sessionId)
            if (session == null) {
                stored.revoked = true
                refreshRepository.save(stored)
                throw unauthorized("Invalid refresh token")
            }
        }

        // Rotate: revoke presented token, issue a new one in the same family.
        stored.revoked = true
        refreshRepository.save(stored)
        val newToken = jwtTokenService.newRefreshToken()
        refreshRepository.save(
            RefreshTokenEntity().apply {
                userId = stored.userId
                sessionId = stored.sessionId
                tokenHash = newToken.hash
                family = stored.family
                expiresAt = now.plus(jwtTokenService.refreshTtl())
            }
        )

        val accessToken = jwtTokenService.issueAccessToken(
            userId = user.id,
            role = user.role,
            subRole = user.subRole,
            sessionId = stored.sessionId,
        )
        return RefreshResponse(
            accessToken = accessToken,
            refreshToken = newToken.raw,
            expiresIn = jwtTokenService.accessTtlSeconds(),
        )
    }

    @Transactional
    fun logout(currentUser: CurrentUser) {
        val tokens = currentUser.sessionId?.let { refreshRepository.findAllBySessionIdAndRevokedFalse(it) }
            ?: refreshRepository.findAllByUserIdAndRevokedFalse(currentUser.userId)
        tokens.forEach { it.revoked = true }
        refreshRepository.saveAll(tokens)

        currentUser.sessionId?.let { sessionId ->
            sessionRepository.findByIdAndIsActiveTrue(sessionId)?.let {
                it.isActive = false
                sessionRepository.save(it)
            }
        }
    }

    /**
     * Role/account switching (doc 01 §5.3): a parent may switch into a linked
     * child's session on the same device. Tokens are issued for the child.
     */
    @Transactional
    fun switchSession(
        currentUser: CurrentUser,
        request: com.afrithecus.brainbox.api.auth.web.SwitchSessionRequest,
        deviceId: String?,
    ): AuthResponse {
        if (currentUser.role != Role.PARENT) {
            throw ApiException(ApiErrorCode.FORBIDDEN, "Only parents can switch to a child session")
        }
        val childId = runCatching { UUID.fromString(request.targetUserId) }.getOrNull()
            ?: throw invalidArgument("targetUserId is not a valid identifier")
        val child = userRepository.findById(childId).orElse(null)
            ?: throw notFound("Child account not found")
        if (child.role != Role.STUDENT) throw invalidArgument("targetRole must match a STUDENT account")

        val linked = userRepository.findByParentUserId(currentUser.userId)
        if (linked.none { it.id == child.id }) {
            throw ApiException(ApiErrorCode.FORBIDDEN, "Child is not linked to this parent")
        }
        if (request.targetRole.trim().uppercase() != Role.STUDENT.name) {
            throw invalidArgument("targetRole does not match the target account")
        }
        return issueAuthResponse(child, deviceId, includeTokens = true, message = "Session switched")
    }

    @Transactional
    fun me(currentUser: CurrentUser): AuthResponse {
        val user = userRepository.findById(currentUser.userId).orElse(null)
            ?: throw ApiException(ApiErrorCode.UNAUTHORIZED, "Account no longer exists")
        return issueAuthResponse(user, currentUser.deviceId, includeTokens = false, message = "OK")
    }

    /** Token-free account view used by the approval/lifecycle responses. */
    @Transactional(readOnly = true)
    fun accountResponse(user: UserEntity, message: String): AuthResponse =
        issueAuthResponse(user, null, includeTokens = false, message = message)

    // ------------------------------------------------------------- internals

    /** Builds the login-shaped response and (optionally) registers a session. */
    fun issueAuthResponse(
        user: UserEntity,
        deviceId: String?,
        includeTokens: Boolean,
        message: String,
    ): AuthResponse {
        val sessionId = if (includeTokens) registerSession(user, deviceId) else null
        val subscriptionView = subscriptionService.view(user.id)

        val linkedChildren = if (user.role == Role.PARENT) {
            userRepository.findByParentUserId(user.id).map { userPayloadFactory.toPayload(it) }
        } else {
            null
        }

        val accessLevel = Entitlements.accessLevel(
            role = user.role,
            subscriptionStatus = SubscriptionStatus.valueOf(subscriptionView.status),
            subscriptionTier = SubscriptionTier.valueOf(subscriptionView.tier),
            isVerified = user.isVerified,
        )

        var sessionToken: String? = null
        var refreshToken: String? = null
        if (includeTokens) {
            sessionToken = jwtTokenService.issueAccessToken(
                userId = user.id,
                role = user.role,
                subRole = user.subRole,
                sessionId = sessionId,
                deviceId = cleanDeviceId(deviceId),
            )
            val generated = jwtTokenService.newRefreshToken()
            refreshRepository.save(
                RefreshTokenEntity().apply {
                    userId = user.id
                    this.sessionId = sessionId
                    tokenHash = generated.hash
                    family = UUID.randomUUID()
                    expiresAt = clock.instant().plus(jwtTokenService.refreshTtl())
                }
            )
            refreshToken = generated.raw
        }

        return AuthResponse(
            success = true,
            message = message,
            sessionToken = sessionToken,
            refreshToken = refreshToken,
            user = userPayloadFactory.toPayload(user),
            subscription = SubscriptionPayload(
                userId = user.id.toString(),
                status = subscriptionView.status,
                tier = subscriptionView.tier,
                expiryDate = subscriptionView.expiryDate,
                totalPaid = subscriptionView.totalPaid,
            ),
            accessLevel = accessLevel.name,
            linkedChildren = linkedChildren,
        )
    }

    /**
     * Device session policy (doc 01 §5.1). ADMIN has no device-session row; the
     * users.user_sessions.role CHECK only permits STUDENT/TEACHER/PARENT.
     */
    private fun registerSession(user: UserEntity, deviceId: String?): UUID? {
        if (user.role == Role.ADMIN) return null
        val role = SessionRole.valueOf(user.role.name)
        val device = cleanDeviceId(deviceId)
        val now = clock.instant()

        sessionRepository.findTopByUserIdAndDeviceIdAndIsActiveTrueOrderByLastActiveAtDesc(user.id, device)
            ?.let { existing ->
                existing.role = role
                existing.lastActiveAt = now
                sessionRepository.save(existing)
                return existing.id
            }

        val cap = if (role == SessionRole.STUDENT) STUDENT_SESSION_CAP else 1
        val active = sessionRepository.findAllByUserIdAndIsActiveTrueOrderByLastActiveAtAscIdAsc(user.id)
        if (active.size >= cap) {
            val evictCount = active.size - (cap - 1)
            active.take(evictCount).forEach {
                it.isActive = false
                sessionRepository.save(it)
            }
        }

        val session = UserSessionEntity().apply {
            this.userId = user.id
            this.deviceId = device
            this.role = role
            lastActiveAt = now
            createdAt = now
        }
        sessionRepository.save(session)
        return session.id
    }

    private fun resolveByIdentifier(identifier: String): UserEntity? {
        if (identifier.isBlank()) return null
        val phone = userRepository.findByPhoneNumber(identifier)
        if (phone != null) return phone
        if (identifier.contains('@')) {
            return userRepository.findByEmail(identifier.lowercase())
        }
        return userRepository.findByStudentAdmissionNumber(identifier)
    }

    private fun parseSignupRole(value: String): Role {
        val role = runCatching { Role.valueOf(value.trim().uppercase()) }.getOrNull()
        if (role == null || role == Role.ADMIN) {
            throw invalidArgument("role must be one of STUDENT, TEACHER, PARENT")
        }
        return role
    }

    private fun resolveSchool(
        requestedId: String?,
        requestedName: String?,
        teacherSchoolId: UUID?,
    ): SchoolEntity? {
        if (requestedId != null) {
            val id = runCatching { UUID.fromString(requestedId) }.getOrNull()
                ?: throw invalidArgument("schoolId is not a valid identifier")
            return schoolRepository.findById(id).orElseThrow { notFound("School not found") }
        }
        if (requestedName != null) {
            return schoolRepository.findByNameIgnoreCase(requestedName) ?: schoolRepository.save(
                SchoolEntity().apply { name = requestedName }
            )
        }
        return teacherSchoolId?.let { id -> schoolRepository.findById(id).orElse(null) }
    }

    private fun generateAdmissionNumber(): String {
        val epoch = clock.millis() / 1000
        repeat(MAX_ADMISSION_ATTEMPTS) {
            val candidate = "BB-" + epoch + "-" + "%04d".format(random.nextInt(10000))
            if (userRepository.findByStudentAdmissionNumber(candidate) == null) return candidate
        }
        throw ApiException(ApiErrorCode.CONFLICT, "Could not allocate an admission number, retry")
    }

    private fun cleanDeviceId(deviceId: String?): String =
        deviceId?.trim()?.take(128)?.takeIf { it.isNotEmpty() } ?: "unknown-device"

    private fun unauthorized(message: String) = ApiException(ApiErrorCode.UNAUTHORIZED, message)

    private companion object {
        const val STUDENT_SESSION_CAP = 3
        const val MAX_ADMISSION_ATTEMPTS = 20
        val random = SecureRandom()
    }
}
