package com.afrithecus.brainbox.api.identity

import com.afrithecus.brainbox.api.common.error.ApiErrorCode
import com.afrithecus.brainbox.api.common.error.ApiException
import com.afrithecus.brainbox.api.common.error.invalidArgument
import com.afrithecus.brainbox.api.common.error.notFound
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.model.CurrentUser
import com.afrithecus.brainbox.api.identity.model.Role
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Who may operate a platform console, and on what evidence.
 *
 * The ADMIN role alone is not enough: every console action needs the matching
 * [PlatformPermission] on the account, which only a [PlatformPermission.PLATFORM_ADMIN] can
 * grant. That keeps "an admin" from being the same thing as "an operator who can disable
 * upload scanning or destroy a quarantined object".
 *
 * **Secure by default.** No account holds a permission until one is granted. The first
 * operator is bootstrapped from configuration
 * (`app.console.bootstrap-operator-phones`, comma-separated phone numbers), which is
 * deliberately explicit rather than "the first admin wins"; when no operator exists at all the
 * application logs a warning naming that setting, so an unoperable console is loud rather
 * than silent.
 *
 * Every grant and revoke is audited.
 */
@Service
class PlatformAccessService(
    private val users: UserRepository,
    private val audit: AuditLogService,
    private val roles: com.afrithecus.brainbox.api.identity.repository.ConsoleRoleRepository,
    @Value("\${app.console.bootstrap-operator-phones:}") private val bootstrapPhones: String,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * The capabilities an account actually holds: its console role's set plus any per-account
     * extras. This is what the console gates on and what `/admin/console/me` reports.
     */
    fun capabilitiesOf(user: UserEntity): Set<PlatformPermission> {
        val roleCapabilities = user.consoleRoleId
            ?.let { roles.findById(it).orElse(null) }
            ?.let { PlatformPermission.parse(it.capabilities) }
            ?: emptySet()
        return roleCapabilities + PlatformPermission.parse(user.platformPermissions)
    }

    /** Alias kept for callers that think in "permissions": the effective set. */
    fun permissionsOf(user: UserEntity): Set<PlatformPermission> = capabilitiesOf(user)

    /** The capabilities granted to the account itself, ignoring its role. */
    fun extraPermissionsOf(user: UserEntity): Set<PlatformPermission> =
        PlatformPermission.parse(user.platformPermissions)

    /** The display name of a console role, for the caller's own identity payload. */
    fun roleName(roleId: UUID): String? = roles.findById(roleId).orElse(null)?.name

    fun has(user: UserEntity, permission: PlatformPermission): Boolean =
        PlatformPermission.satisfies(capabilitiesOf(user), permission)

    /** True when the account may operate the console at all. */
    fun isOperator(user: UserEntity): Boolean = permissionsOf(user).isNotEmpty()

    /** Requires a platform permission on the calling account. */
    fun requirePermission(current: CurrentUser, permission: PlatformPermission): UserEntity {
        val user = load(current)
        if (!has(user, permission)) {
            throw ApiException(
                ApiErrorCode.FORBIDDEN,
                "This console action requires the " + permission.name + " permission",
            )
        }
        return user
    }

    fun requireRead(current: CurrentUser): UserEntity =
        requirePermission(current, PlatformPermission.CONSOLE_READ)

    /** The media security console reads: scan logs, alerts, quarantine, posture. */
    fun requireSecurityRead(current: CurrentUser): UserEntity =
        requirePermission(current, PlatformPermission.SECURITY_READ)

    fun requireSecurityOperate(current: CurrentUser): UserEntity =
        requirePermission(current, PlatformPermission.SECURITY_OPERATE)

    fun requirePlatformAdmin(current: CurrentUser): UserEntity =
        requirePermission(current, PlatformPermission.PLATFORM_ADMIN)

    /** Every admin account and the permissions it holds, for the operator manager. */
    @Transactional(readOnly = true)
    fun operators(): List<PlatformOperator> =
        users.findAll().filter { it.role == Role.ADMIN }.map { PlatformOperator(it, capabilitiesOf(it)) }

    /** Grants permissions to an admin account; audited. */
    @Transactional
    fun grant(actor: CurrentUser, userIdRaw: String, permissions: Collection<String>): PlatformOperator {
        val actorUser = requirePlatformAdmin(actor)
        val target = requireAdminAccount(userIdRaw)
        val wanted = permissions.map { parseStrict(it) }.toSet()
        if (wanted.isEmpty()) throw invalidArgument("At least one permission is required")
        val updated = extraPermissionsOf(target) + wanted
        target.platformPermissions = PlatformPermission.format(updated)
        users.save(target)
        audit.recordPlatform(
            actor = actorUser,
            action = "platform_permissions_granted",
            target = "user:" + target.id,
            detail = "granted " + PlatformPermission.format(wanted) + " to " + target.name,
        )
        return PlatformOperator(target, capabilitiesOf(target))
    }

    /** Revokes permissions from an admin account; audited. Revoking the last one clears the column. */
    @Transactional
    fun revoke(actor: CurrentUser, userIdRaw: String, permissions: Collection<String>): PlatformOperator {
        val actorUser = requirePlatformAdmin(actor)
        val target = requireAdminAccount(userIdRaw)
        val unwanted = permissions.map { parseStrict(it) }.toSet()
        if (unwanted.isEmpty()) throw invalidArgument("At least one permission is required")
        // An operator cannot remove their own PLATFORM_ADMIN while no other operator holds it:
        // that would lock every operator out of the console with no way back in.
        if (target.id == actorUser.id && PlatformPermission.PLATFORM_ADMIN in unwanted) {
            val others = operators().count { it.userId != target.id && it.has(PlatformPermission.PLATFORM_ADMIN) }
            if (others == 0) {
                throw invalidArgument("Grant PLATFORM_ADMIN to another operator before removing your own")
            }
        }
        val updated = extraPermissionsOf(target) - unwanted
        target.platformPermissions = if (updated.isEmpty()) null else PlatformPermission.format(updated)
        users.save(target)
        audit.recordPlatform(
            actor = actorUser,
            action = "platform_permissions_revoked",
            target = "user:" + target.id,
            detail = "revoked " + PlatformPermission.format(unwanted) + " from " + target.name,
        )
        return PlatformOperator(target, capabilitiesOf(target))
    }

    /**
     * Grants PLATFORM_ADMIN to every configured bootstrap operator at startup. Idempotent, and
     * it says so loudly: this is the documented break-glass path, not a silent default.
     */
    @EventListener(ApplicationReadyEvent::class)
    @Transactional
    fun bootstrapOperators() {
        val phones = bootstrapPhones.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        phones.forEach { phone ->
            val user = users.findByPhoneNumber(phone)
            if (user == null) {
                log.warn("console bootstrap: no account with phone {} (set CONSOLE_BOOTSTRAP_OPERATOR_PHONES)", phone)
                return@forEach
            }
            if (user.role != Role.ADMIN) {
                log.warn("console bootstrap: {} is not an ADMIN account, skipping", phone)
                return@forEach
            }
            if (has(user, PlatformPermission.PLATFORM_ADMIN)) return@forEach
            user.platformPermissions =
                PlatformPermission.format(extraPermissionsOf(user) + PlatformPermission.PLATFORM_ADMIN)
            users.save(user)
            audit.recordPlatform(
                actor = null,
                action = "platform_permissions_bootstrapped",
                target = "user:" + user.id,
                detail = "PLATFORM_ADMIN granted by configuration to " + user.name,
            )
            log.warn(
                "console bootstrap: granted PLATFORM_ADMIN to {} — remove it from configuration once operators exist",
                phone,
            )
        }
        if (operators().none { it.has(PlatformPermission.PLATFORM_ADMIN) }) {
            log.warn(
                "console security: no account holds PLATFORM_ADMIN, so no console operation is possible; " +
                    "set CONSOLE_BOOTSTRAP_OPERATOR_PHONES to bootstrap one",
            )
        }
    }

    private fun load(current: CurrentUser): UserEntity =
        users.findById(current.userId).orElseThrow { notFound("User not found") }

    private fun requireAdminAccount(userIdRaw: String): UserEntity {
        val id = runCatching { UUID.fromString(userIdRaw.trim()) }.getOrNull()
            ?: throw invalidArgument("userId is not a valid identifier")
        val user = users.findById(id).orElse(null) ?: throw notFound("User not found")
        if (user.role != Role.ADMIN) {
            throw invalidArgument("Platform permissions can only be granted to an ADMIN account")
        }
        return user
    }

    private fun parseStrict(raw: String): PlatformPermission {
        val normalized = raw.trim().uppercase()
        return PlatformPermission.entries.firstOrNull { it.name == normalized }
            ?: throw invalidArgument("Unknown permission '" + raw.trim() + "'")
    }
}

/** One operator row as the console's operator manager reads it. */
data class PlatformOperator(val user: UserEntity, val permissions: Set<PlatformPermission>) {
    val userId: UUID get() = user.id
    val name: String get() = user.name
    val phoneNumber: String get() = user.phoneNumber.orEmpty()
    fun has(permission: PlatformPermission): Boolean = PlatformPermission.satisfies(permissions, permission)
}
