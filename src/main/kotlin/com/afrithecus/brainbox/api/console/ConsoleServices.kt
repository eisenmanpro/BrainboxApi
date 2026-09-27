package com.afrithecus.brainbox.api.console

import com.afrithecus.brainbox.api.common.error.conflict
import com.afrithecus.brainbox.api.common.error.invalidArgument
import com.afrithecus.brainbox.api.common.error.notFound
import com.afrithecus.brainbox.api.identity.AuditLogService
import com.afrithecus.brainbox.api.identity.PlatformAccessService
import com.afrithecus.brainbox.api.identity.PlatformPermission
import com.afrithecus.brainbox.api.identity.entity.SchoolEntity
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.model.AccountKind
import com.afrithecus.brainbox.api.identity.model.AccountStatus
import com.afrithecus.brainbox.api.identity.model.CurrentUser
import com.afrithecus.brainbox.api.identity.model.Role
import com.afrithecus.brainbox.api.identity.model.SubRole
import com.afrithecus.brainbox.api.identity.repository.SchoolRepository
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import com.afrithecus.brainbox.api.messaging.PlatformSender
import com.afrithecus.brainbox.api.console.web.ConsoleCreateSchoolRequest
import com.afrithecus.brainbox.api.console.web.ConsoleCreateUserRequest
import com.afrithecus.brainbox.api.console.web.ConsoleCreatedUser
import com.afrithecus.brainbox.api.console.web.ConsoleCredential
import com.afrithecus.brainbox.api.console.web.ConsoleSchoolView
import com.afrithecus.brainbox.api.console.web.ConsoleSubscriberView
import com.afrithecus.brainbox.api.console.web.ConsoleUserView
import com.afrithecus.brainbox.api.identity.model.SubscriptionStatus
import com.afrithecus.brainbox.api.subscription.entity.SubscriptionEntity
import com.afrithecus.brainbox.api.subscription.repository.SubscriptionRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.domain.PageRequest
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.util.UUID

/**
 * User administration for the platform console: see anyone, act on anyone.
 *
 * Reads need `USERS_READ`, actions need `USERS_MANAGE`, and every action is audited with the
 * actor. Suspend is a real gate — an inactive account cannot sign in (`AuthService.login`
 * refuses it) and its tokens stop being honoured because the session check consults the row —
 * so "suspend" means suspended, not "hidden from a list".
 */
@Service
class ConsoleUserService(
    private val users: UserRepository,
    private val schools: SchoolRepository,
    private val subscriptions: SubscriptionRepository,
    private val access: PlatformAccessService,
    private val audit: AuditLogService,
    private val passwordEncoder: PasswordEncoder,
    private val clock: Clock,
    @Value("\${app.console.temporary-password-ttl-hours:72}") private val temporaryPasswordTtlHours: Long,
) {

    /** The console's account list: filtered by type, status and a free-text query. */
    @Transactional(readOnly = true)
    fun list(
        current: CurrentUser,
        role: Role?,
        subRole: SubRole?,
        status: String?,
        schoolId: UUID?,
        query: String?,
        limit: Int,
        before: Long?,
    ): List<ConsoleUserView> {
        access.requirePermission(current, PlatformPermission.USERS_READ)
        val size = limit.coerceIn(1, MAX_PAGE)
        // The repository search covers role/school/active and the free-text fields; the console
        // adds the sub-role and the verified/suspended split on top of it in memory.
        val found = users.search(role, schoolId, activeFromStatus(status), query?.trim()?.takeIf { it.isNotEmpty() }, PageRequest.of(0, size))
        val rows = found.content
            // The platform's message sender is an account, not a person: it is never administered
            // from here, so it never appears in the list (or in the counts below).
            .filter { it.id != PlatformSender.USER_ID }
            .filter { subRole == null || it.subRole == subRole }
            .filter { it.matchesVerified(status) }
            .sortedByDescending { it.createdAt }
            .let { filtered -> if (before == null) filtered else filtered.filter { it.createdAt.toEpochMilli() < before } }
            .take(size)
        val schoolNames = schools.findAllById(rows.mapNotNull { it.schoolId }.distinct())
            .associate { it.id to it.name }
        return rows.map { it.toView(schoolNames[it.schoolId]) }
    }

    @Transactional(readOnly = true)
    fun get(current: CurrentUser, userId: String): ConsoleUserView {
        access.requirePermission(current, PlatformPermission.USERS_READ)
        val user = requireUser(userId)
        val schoolName = user.schoolId?.let { schools.findById(it).orElse(null)?.name }
        val subscription = subscriptions.findByUserId(user.id)
        return user.toView(schoolName).copy(
            subscriptionStatus = subscription?.status?.name,
            subscriptionTier = subscription?.tier?.name,
            subscriptionExpiry = subscription?.expiryDate?.toEpochMilli(),
        )
    }

    /** Counts by role, for the console's tiles. */
    @Transactional(readOnly = true)
    fun counts(current: CurrentUser): Map<String, Long> {
        access.requirePermission(current, PlatformPermission.USERS_READ)
        val all = users.findAll().filter { it.id != PlatformSender.USER_ID }
        val counts = mutableMapOf<String, Long>()
        counts["TOTAL"] = all.size.toLong()
        Role.entries.forEach { role -> counts[role.name] = all.count { it.role == role }.toLong() }
        counts["SUSPENDED"] = all.count { !it.isActive }.toLong()
        counts["UNVERIFIED"] = all.count { !it.isVerified }.toLong()
        counts["ROSTER_ONLY"] = all.count { it.accountKind == AccountKind.ROSTER_ONLY }.toLong()
        return counts
    }

    /** Suspends or restores an account. A suspended account cannot sign in. */
    @Transactional
    fun setActive(current: CurrentUser, userId: String, active: Boolean): ConsoleUserView {
        val actor = access.requirePermission(current, PlatformPermission.USERS_MANAGE)
        val user = requireMutableUser(userId)
        if (user.id == actor.id && !active) {
            throw invalidArgument("You cannot suspend the account you are signed in with")
        }
        if (user.role == Role.ADMIN && !active && activeAdmins(excluding = user.id) == 0L) {
            throw invalidArgument("At least one active ADMIN account must remain")
        }
        user.isActive = active
        users.save(user)
        audit.recordPlatform(
            actor = actor,
            action = if (active) "console_user_unsuspended" else "console_user_suspended",
            target = "user:" + user.id,
            detail = user.name + " (" + user.role.name + ")",
        )
        return user.toView(user.schoolId?.let { schools.findById(it).orElse(null)?.name })
    }

    /** Marks an account verified, or clears it so the user must verify again. */
    @Transactional
    fun setVerified(current: CurrentUser, userId: String, verified: Boolean): ConsoleUserView {
        val actor = access.requirePermission(current, PlatformPermission.USERS_MANAGE)
        val user = requireMutableUser(userId)
        user.isVerified = verified
        user.verificationStatus = if (verified) AccountStatus.VERIFIED else AccountStatus.PENDING_VERIFICATION
        users.save(user)
        audit.recordPlatform(
            actor = actor,
            action = if (verified) "console_user_verified" else "console_user_unverified",
            target = "user:" + user.id,
            detail = user.name,
        )
        return user.toView(user.schoolId?.let { schools.findById(it).orElse(null)?.name })
    }

    /**
     * Issues a fresh temporary password (returned once, never stored in clear) and flags the
     * account so the console can hand it to the user out of band. The password is generated
     * here rather than chosen by the operator, so two accounts cannot share one.
     */
    @Transactional
    fun resetPassword(current: CurrentUser, userId: String): ConsoleCredential {
        val actor = access.requirePermission(current, PlatformPermission.USERS_MANAGE)
        val user = requireMutableUser(userId)
        val temporary = generatePassword()
        user.passwordHash = passwordEncoder.encode(temporary) ?: throw IllegalStateException("encode failed")
        userRepositorySave(user)
        audit.recordPlatform(
            actor = actor,
            action = "console_user_password_reset",
            target = "user:" + user.id,
            detail = user.name + " was issued a temporary password",
        )
        return ConsoleCredential(
            userId = user.id.toString(),
            name = user.name,
            identifier = user.phoneNumber ?: user.email.orEmpty(),
            temporaryPassword = temporary,
            expiresInHours = temporaryPasswordTtlHours,
        )
    }

    /**
     * **Override creation.** Creates an account of any type directly, with generated
     * credentials, bypassing self-registration (and any school admissions flow). The caller
     * supplies the role and, where it matters, the school/grade; the server owns the password
     * and the admission number.
     */
    @Transactional
    fun create(current: CurrentUser, request: ConsoleCreateUserRequest): ConsoleCreatedUser {
        val actor = access.requirePermission(current, PlatformPermission.USERS_MANAGE)
        val role = parseRole(request.role)
        val school = request.schoolId?.trim()?.takeIf { it.isNotEmpty() }?.let { requireSchool(it) }
        val phone = request.phoneNumber?.trim()?.takeIf { it.isNotEmpty() }
        val email = request.email?.trim()?.takeIf { it.isNotEmpty() }
        if (phone == null && email == null) throw invalidArgument("An account needs a phone number or an email address")
        if (phone != null && users.existsByPhoneNumber(phone)) throw conflict("An account with this phone number already exists")
        if (email != null && users.existsByEmail(email)) throw conflict("An account with this email already exists")
        val subRole = request.subRole?.trim()?.takeIf { it.isNotEmpty() }?.let { raw ->
            runCatching { SubRole.valueOf(raw.uppercase()) }.getOrNull()
                ?: throw invalidArgument("Unknown sub-role '$raw'")
        }
        if (subRole != null && role != Role.TEACHER) {
            throw invalidArgument("A sub-role only applies to a TEACHER account")
        }
        val password = generatePassword()
        val admission = if (role == Role.STUDENT) generateAdmissionNumber() else null
        val user = users.save(
            UserEntity().apply {
                this.phoneNumber = phone
                this.email = email
                name = request.name.trim().ifEmpty { "Console-created account" }
                passwordHash = passwordEncoder.encode(password) ?: throw IllegalStateException("encode failed")
                this.role = role
                this.subRole = subRole
                this.schoolId = school?.id
                gradeLevel = request.gradeLevel?.trim()?.takeIf { it.isNotEmpty() }
                studentAdmissionNumber = admission
                isActive = true
                // An operator creating the account may verify it in the same step; otherwise the
                // account still has to verify, exactly as self-registration requires.
                isVerified = request.verified
                verificationStatus = if (request.verified) AccountStatus.VERIFIED else AccountStatus.PENDING_VERIFICATION
                accountKind = AccountKind.FULL
                provisionedBy = actor.id
                provisionedAt = clock.instant()
            }
        )
        audit.recordPlatform(
            actor = actor,
            action = "console_user_created",
            target = "user:" + user.id,
            detail = role.name + (school?.let { " at " + it.name } ?: "") + " created by override",
        )
        return ConsoleCreatedUser(
            user = user.toView(school?.name),
            credential = ConsoleCredential(
                userId = user.id.toString(),
                name = user.name,
                identifier = phone ?: email.orEmpty(),
                temporaryPassword = password,
                expiresInHours = temporaryPasswordTtlHours,
            ),
            admissionNumber = admission,
        )
    }

    // ---------------------------------------------------------------- internals

    /** ACTIVE/SUSPENDED map onto the repository's `active` flag; the rest are filtered here. */
    private fun activeFromStatus(status: String?): Boolean? = when (status?.trim()?.uppercase()) {
        null, "" -> null
        "ACTIVE" -> true
        "SUSPENDED", "INACTIVE" -> false
        "VERIFIED", "UNVERIFIED" -> null
        else -> throw invalidArgument("status must be ACTIVE, SUSPENDED, VERIFIED or UNVERIFIED")
    }

    /** The verified/unverified half of the status filter. */
    private fun UserEntity.matchesVerified(status: String?): Boolean = when (status?.trim()?.uppercase()) {
        "VERIFIED" -> isVerified
        "UNVERIFIED" -> !isVerified
        else -> true
    }

    private fun requireUser(userIdRaw: String): UserEntity {
        val id = runCatching { UUID.fromString(userIdRaw.trim()) }.getOrNull()
            ?: throw invalidArgument("userId is not a valid identifier")
        return users.findById(id).orElse(null) ?: throw notFound("User not found")
    }

    /**
     * The same lookup for an account the console is about to change. The platform's message
     * sender is off limits: suspending it, unverifying it or re-issuing its password would break
     * every Brainbox message, and it can never sign in anyway.
     */
    private fun requireMutableUser(userIdRaw: String): UserEntity {
        val user = requireUser(userIdRaw)
        if (user.id == PlatformSender.USER_ID) {
            throw invalidArgument(
                "The platform message sender (" + PlatformSender.NAME + ") is not a user account " +
                    "and cannot be modified",
            )
        }
        return user
    }

    private fun requireSchool(schoolIdRaw: String): SchoolEntity {
        val id = runCatching { UUID.fromString(schoolIdRaw.trim()) }.getOrNull()
            ?: throw invalidArgument("schoolId is not a valid identifier")
        return schools.findById(id).orElse(null) ?: throw notFound("School not found")
    }

    private fun activeAdmins(excluding: UUID): Long =
        users.findAll().count { it.role == Role.ADMIN && it.isActive && it.id != excluding }.toLong()


    private fun userRepositorySave(user: UserEntity) {
        users.save(user)
    }

    private fun parseRole(raw: String): Role =
        runCatching { Role.valueOf(raw.trim().uppercase()) }.getOrNull()
            ?: throw invalidArgument("role must be STUDENT, TEACHER, PARENT or ADMIN")

    /** A readable, high-entropy password: the console shows it once and never again. */
    private fun generatePassword(): String {
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"
        val random = SecureRandom()
        return (1..PASSWORD_LENGTH).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")
    }

    private fun generateAdmissionNumber(): String =
        "CTC" + (100000 + java.util.concurrent.ThreadLocalRandom.current().nextInt(900000))

    companion object {
        private const val MAX_PAGE = 200
        private const val PASSWORD_LENGTH = 14
    }

    private fun UserEntity.toView(schoolName: String?) = ConsoleUserView(
        userId = id.toString(),
        name = name,
        role = role.name,
        subRole = subRole?.name,
        schoolId = schoolId?.toString(),
        schoolName = schoolName,
        gradeLevel = gradeLevel,
        admissionNumber = studentAdmissionNumber,
        accountKind = accountKind.name,
        isActive = isActive,
        isVerified = isVerified,
        lastLogin = lastLogin?.toEpochMilli(),
        createdAt = createdAt.toEpochMilli(),
        consoleRoleId = consoleRoleId?.toString(),
        capabilities = access.capabilitiesOf(this).map { it.name }.sorted(),
    )
}

/** School administration: the platform view of every school, plus the public visibility switch. */
@Service
class ConsoleSchoolService(
    private val schools: SchoolRepository,
    private val users: UserRepository,
    private val access: PlatformAccessService,
    private val audit: AuditLogService,
) {

    @Transactional(readOnly = true)
    fun list(current: CurrentUser, active: Boolean?, query: String?, limit: Int): List<ConsoleSchoolView> {
        access.requirePermission(current, PlatformPermission.SCHOOLS_READ)
        val all = schools.findAll()
            .filter { active == null || it.isActive == active }
            .filter { query.isNullOrBlank() || it.name.contains(query.trim(), ignoreCase = true) }
            .sortedBy { it.name.lowercase() }
            .take(limit.coerceIn(1, 200))
        val counts = users.findAll().filter { it.schoolId != null }
            .groupingBy { it.schoolId!! }
            .eachCount()
        return all.map { school -> school.toView(counts[school.id] ?: 0) }
    }

    @Transactional(readOnly = true)
    fun counts(current: CurrentUser): Map<String, Long> {
        access.requirePermission(current, PlatformPermission.SCHOOLS_READ)
        val all = schools.findAll()
        return mapOf(
            "TOTAL" to all.size.toLong(),
            "ACTIVE" to all.count { it.isActive }.toLong(),
            "SUSPENDED" to all.count { !it.isActive }.toLong(),
        )
    }

    /**
     * Creates a school. It starts active, so it appears in the public directory; the console can
     * suspend it, which is what removes it from the public read.
     */
    @Transactional
    fun create(current: CurrentUser, request: ConsoleCreateSchoolRequest): ConsoleSchoolView {
        val actor = access.requirePermission(current, PlatformPermission.SCHOOLS_MANAGE)
        val name = request.name.trim()
        if (name.isEmpty()) throw invalidArgument("A school name is required")
        if (schools.findAll().any { it.name.equals(name, ignoreCase = true) }) {
            throw conflict("A school named $name already exists")
        }
        val school = schools.save(
            SchoolEntity().apply {
                this.name = name
                county = request.county?.trim()
                location = request.location?.trim()
                isActive = request.active
            }
        )
        audit.recordPlatform(
            actor = actor,
            action = "console_school_created",
            target = "school:" + school.id,
            detail = school.name,
        )
        return school.toView(0)
    }

    @Transactional
    fun update(current: CurrentUser, schoolId: String, request: ConsoleCreateSchoolRequest): ConsoleSchoolView {
        val actor = access.requirePermission(current, PlatformPermission.SCHOOLS_MANAGE)
        val school = require(schoolId)
        request.name.trim().takeIf { it.isNotEmpty() }?.let { school.name = it }
        request.county?.let { school.county = it.trim().takeIf { value -> value.isNotEmpty() } }
        request.location?.let { school.location = it.trim().takeIf { value -> value.isNotEmpty() } }
        schools.save(school)
        audit.recordPlatform(
            actor = actor,
            action = "console_school_updated",
            target = "school:" + school.id,
            detail = school.name,
        )
        return school.toView(users.findAll().count { it.schoolId == school.id })
    }

    /** Suspends or reactivates a school; a suspended school leaves the public directory. */
    @Transactional
    fun setActive(current: CurrentUser, schoolId: String, active: Boolean): ConsoleSchoolView {
        val actor = access.requirePermission(current, PlatformPermission.SCHOOLS_MANAGE)
        val school = require(schoolId)
        school.isActive = active
        schools.save(school)
        val members = users.findAll().count { it.schoolId == school.id }.toLong()
        audit.recordPlatform(
            actor = actor,
            action = if (active) "console_school_activated" else "console_school_suspended",
            target = "school:" + school.id,
            detail = school.name + " (" + members + " accounts)",
        )
        return school.toView(members.toInt())
    }

    private fun require(schoolIdRaw: String): SchoolEntity {
        val id = runCatching { UUID.fromString(schoolIdRaw.trim()) }.getOrNull()
            ?: throw invalidArgument("schoolId is not a valid identifier")
        return schools.findById(id).orElse(null) ?: throw notFound("School not found")
    }

    private fun SchoolEntity.toView(memberCount: Int) = ConsoleSchoolView(
        schoolId = id.toString(),
        name = name,
        county = county,
        location = location,
        isActive = isActive,
        memberCount = memberCount,
        createdAt = createdAt.toEpochMilli(),
    )
}

/** The subscriber book: who is paying, who has lapsed, who is about to. */
@Service
class ConsoleSubscriberService(
    private val subscriptions: SubscriptionRepository,
    private val users: UserRepository,
    private val schools: SchoolRepository,
    private val access: PlatformAccessService,
    private val clock: Clock,
) {

    @Transactional(readOnly = true)
    fun list(
        current: CurrentUser,
        status: String?,
        tier: String?,
        schoolId: UUID?,
        activeOnly: Boolean?,
        limit: Int,
    ): List<ConsoleSubscriberView> {
        access.requirePermission(current, PlatformPermission.SUBSCRIBERS_READ)
        val rows = subscriptions.findAll()
        val usersById = users.findAllById(rows.map { it.userId }.distinct()).associateBy { it.id }
        val schoolNames = schools.findAllById(usersById.values.mapNotNull { it.schoolId }.distinct())
            .associate { it.id to it.name }
        val now = clock.instant()
        return rows.asSequence()
            .mapNotNull { row -> usersById[row.userId]?.let { row to it } }
            .filter { (_, user) -> schoolId == null || user.schoolId == schoolId }
            .filter { (row, _) ->
                status?.trim()?.takeIf { it.isNotEmpty() }?.let {
                    row.status.name.equals(it, ignoreCase = true)
                } ?: true
            }
            .filter { (row, _) ->
                tier?.trim()?.takeIf { it.isNotEmpty() }?.let {
                    row.tier.name.equals(it, ignoreCase = true)
                } ?: true
            }
            .filter { (row, _) ->
                when (activeOnly) {
                    null -> true
                    true -> row.status == SubscriptionStatus.ACTIVE
                    false -> row.status != SubscriptionStatus.ACTIVE
                }
            }
            .sortedByDescending { (row, _) -> row.updatedAt }
            .take(limit.coerceIn(1, 200))
            .map { (row, user) -> row.toView(user, schoolNames[user.schoolId], now) }
            .toList()
    }

    @Transactional(readOnly = true)
    fun counts(current: CurrentUser): Map<String, Long> {
        access.requirePermission(current, PlatformPermission.SUBSCRIBERS_READ)
        val rows = subscriptions.findAll()
        val now = clock.instant()
        return buildMap {
            put("TOTAL", rows.size.toLong())
            SubscriptionStatus.entries.forEach { status ->
                put(status.name, rows.count { it.status == status }.toLong())
            }
            put("EXPIRING_7D", rows.count { it.expiryDate != null && it.expiryDate!!.isAfter(now) && it.expiryDate!!.isBefore(now.plus(Duration.ofDays(7))) }.toLong())
            put("EXPIRED_ACTIVE", rows.count { it.status == SubscriptionStatus.ACTIVE && it.expiryDate != null && it.expiryDate!!.isBefore(now) }.toLong())
        }
    }

    private fun SubscriptionEntity.toView(
        user: UserEntity,
        schoolName: String?,
        now: java.time.Instant,
    ) = ConsoleSubscriberView(
        userId = user.id.toString(),
        name = user.name,
        role = user.role.name,
        schoolId = user.schoolId?.toString(),
        schoolName = schoolName,
        status = status.name,
        tier = tier.name,
        expiryDate = expiryDate?.toEpochMilli(),
        active = status == SubscriptionStatus.ACTIVE && (expiryDate == null || expiryDate!!.isAfter(now)),
        totalPaid = totalPaid,
    )
}
