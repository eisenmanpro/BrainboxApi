package com.afrithecus.brainbox.api.identity

import com.afrithecus.brainbox.api.common.error.conflict
import com.afrithecus.brainbox.api.common.error.invalidArgument
import com.afrithecus.brainbox.api.common.error.notFound
import com.afrithecus.brainbox.api.identity.entity.ConsoleRoleEntity
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.model.CurrentUser
import com.afrithecus.brainbox.api.identity.model.Role
import com.afrithecus.brainbox.api.identity.repository.ConsoleRoleRepository
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import com.afrithecus.brainbox.api.messaging.PlatformSender
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Console roles: the named capability sets the ADMIN creates and assigns.
 *
 * A role is the everyday way to hand someone console authority, so creating one is itself a
 * privilege ([PlatformPermission.PLATFORM_ADMIN]) and every change is audited. Seeded system
 * roles cannot be renamed or deleted — the capability set is editable — because deleting the
 * role that grants `PLATFORM_ADMIN` would strand the console.
 */
@Service
class ConsoleRoleService(
    private val roles: ConsoleRoleRepository,
    private val users: UserRepository,
    private val access: PlatformAccessService,
    private val audit: AuditLogService,
) {

    @Transactional(readOnly = true)
    fun list(current: CurrentUser): List<ConsoleRoleView> {
        access.requireRead(current)
        return roles.findAll().sortedBy { it.name }.map(::view)
    }

    @Transactional(readOnly = true)
    fun get(current: CurrentUser, roleId: String): ConsoleRoleView {
        access.requireRead(current)
        return view(require(roleId))
    }

    /** The capability catalogue, so the console can render a role editor without hardcoding it. */
    @Transactional(readOnly = true)
    fun capabilities(current: CurrentUser): List<CapabilityView> {
        access.requireRead(current)
        return PlatformPermission.entries.map { CapabilityView(it.name, describe(it)) }
    }

    @Transactional
    fun create(
        current: CurrentUser,
        name: String,
        description: String,
        capabilities: Collection<String>,
    ): ConsoleRoleView {
        val actor = access.requirePlatformAdmin(current)
        val normalized = name.trim().uppercase().replace(Regex("\\s+"), "_")
        if (normalized.isEmpty()) throw invalidArgument("A role name is required")
        if (normalized.length > 64) throw invalidArgument("A role name must be 64 characters or fewer")
        if (roles.findByNameIgnoreCase(normalized) != null) throw conflict("A role named $normalized already exists")
        val wanted = parseAll(capabilities)
        if (wanted.isEmpty()) throw invalidArgument("A role needs at least one capability")
        val row = roles.save(
            ConsoleRoleEntity().apply {
                this.name = normalized
                this.description = description.trim().take(255)
                this.capabilities = PlatformPermission.format(wanted)
                isSystem = false
                createdBy = actor.id
            }
        )
        audit.recordPlatform(
            actor = actor,
            action = "console_role_created",
            target = "role:" + row.id,
            detail = "created " + row.name + " with " + row.capabilities,
        )
        return view(row)
    }

    @Transactional
    fun update(
        current: CurrentUser,
        roleId: String,
        description: String?,
        capabilities: Collection<String>?,
        name: String? = null,
    ): ConsoleRoleView {
        val actor = access.requirePlatformAdmin(current)
        val row = require(roleId)
        name?.trim()?.takeIf { it.isNotEmpty() }?.let { wanted ->
            if (row.isSystem) throw invalidArgument("A system role cannot be renamed")
            val normalized = wanted.uppercase().replace(Regex("\\s+"), "_")
            if (normalized.length > 64) throw invalidArgument("A role name must be 64 characters or fewer")
            roles.findByNameIgnoreCase(normalized)?.takeIf { it.id != row.id }?.let {
                throw conflict("A role named $normalized already exists")
            }
            row.name = normalized
        }
        description?.let { row.description = it.trim().take(255) }
        capabilities?.let { row.capabilities = PlatformPermission.format(parseAll(it)) }
        // The role that grants PLATFORM_ADMIN must keep granting it: a system role cannot be
        // emptied of its power, or the console could lose its last way in.
        if (row.isSystem && row.name == "PLATFORM_OWNER" && !row.capabilities.contains("PLATFORM_ADMIN")) {
            throw invalidArgument("PLATFORM_OWNER must keep PLATFORM_ADMIN")
        }
        roles.save(row)
        audit.recordPlatform(
            actor = actor,
            action = "console_role_updated",
            target = "role:" + row.id,
            detail = row.name + " is now " + row.capabilities,
        )
        return view(row)
    }

    @Transactional
    fun delete(current: CurrentUser, roleId: String): Map<String, String> {
        val actor = access.requirePlatformAdmin(current)
        val row = require(roleId)
        if (row.isSystem) throw invalidArgument("A system role cannot be deleted")
        val holders = users.findAll().filter { it.consoleRoleId == row.id }
        if (holders.isNotEmpty()) {
            throw conflict(
                "This role is assigned to " + holders.size + " account(s); reassign them first",
            )
        }
        roles.delete(row)
        audit.recordPlatform(
            actor = actor,
            action = "console_role_deleted",
            target = "role:" + row.id,
            detail = "deleted " + row.name,
        )
        return mapOf("status" to "DELETED", "roleId" to row.id.toString())
    }

    /** Assigns (or clears) an account's console role; the account must be an ADMIN. */
    @Transactional
    fun assign(current: CurrentUser, userIdRaw: String, roleId: String?): ConsoleAccountView {
        val actor = access.requirePlatformAdmin(current)
        val user = requireAdminAccount(userIdRaw)
        val role = roleId?.trim()?.takeIf { it.isNotEmpty() }?.let { require(it) }
        user.consoleRoleId = role?.id
        users.save(user)
        audit.recordPlatform(
            actor = actor,
            action = "console_role_assigned",
            target = "user:" + user.id,
            detail = user.name + " -> " + (role?.name ?: "no role"),
        )
        return ConsoleAccountView(
            userId = user.id.toString(),
            name = user.name,
            phoneNumber = user.phoneNumber.orEmpty(),
            roleName = role?.name,
            capabilities = access.capabilitiesOf(user).map { it.name }.sorted(),
        )
    }

    /** Every ADMIN account with its console role and effective capabilities. */
    @Transactional(readOnly = true)
    fun accounts(current: CurrentUser): List<ConsoleAccountView> {
        access.requirePlatformAdmin(current)
        val byId = roles.findAll().associateBy { it.id }
        return users.findAll()
            .filter { it.role == Role.ADMIN }
            // The platform message sender is an ADMIN row so it can author messages, but it is
            // not an operator: it holds no capability and must never be offered one.
            .filter { it.id != PlatformSender.USER_ID }
            .sortedBy { it.name }
            .map { user ->
                ConsoleAccountView(
                    userId = user.id.toString(),
                    name = user.name,
                    phoneNumber = user.phoneNumber.orEmpty(),
                    roleName = user.consoleRoleId?.let { byId[it]?.name },
                    capabilities = access.capabilitiesOf(user).map { it.name }.sorted(),
                )
            }
    }

    private fun require(roleId: String): ConsoleRoleEntity {
        val id = runCatching { UUID.fromString(roleId.trim()) }.getOrNull()
            ?: throw invalidArgument("roleId is not a valid identifier")
        return roles.findById(id).orElse(null) ?: throw notFound("Console role not found")
    }

    private fun requireAdminAccount(userIdRaw: String): UserEntity {
        val id = runCatching { UUID.fromString(userIdRaw.trim()) }.getOrNull()
            ?: throw invalidArgument("userId is not a valid identifier")
        val user = users.findById(id).orElse(null) ?: throw notFound("User not found")
        if (user.role != Role.ADMIN) throw invalidArgument("A console role can only be assigned to an ADMIN account")
        return user
    }

    private fun parseAll(capabilities: Collection<String>): Set<PlatformPermission> =
        capabilities.map { raw ->
            val normalized = raw.trim().uppercase()
            PlatformPermission.entries.firstOrNull { it.name == normalized }
                ?: throw invalidArgument("Unknown capability '" + raw.trim() + "'")
        }.toSet()

    private fun view(row: ConsoleRoleEntity) = ConsoleRoleView(
        roleId = row.id.toString(),
        name = row.name,
        description = row.description,
        capabilities = PlatformPermission.parse(row.capabilities).map { it.name }.sorted(),
        isSystem = row.isSystem,
    )

    private fun describe(permission: PlatformPermission): String = when (permission) {
        PlatformPermission.CONSOLE_READ -> "Read the console's own surfaces and the platform audit trail"
        PlatformPermission.PLATFORM_ADMIN -> "Create console roles, assign them and grant capabilities"
        PlatformPermission.USERS_READ -> "List and inspect accounts of every type"
        PlatformPermission.USERS_MANAGE -> "Suspend, unsuspend, verify, reset and create accounts"
        PlatformPermission.SCHOOLS_READ -> "List and inspect schools"
        PlatformPermission.SCHOOLS_MANAGE -> "Create, update, suspend and reactivate schools"
        PlatformPermission.NEWS_MANAGE -> "Write the public news feed"
        PlatformPermission.NOTIFICATIONS_MANAGE -> "Compose, target, schedule and cancel notifications"
        PlatformPermission.MESSAGES_MANAGE -> "Write messages into users' inboxes as Brainbox"
        PlatformPermission.SUBSCRIBERS_READ -> "Read active, inactive and expiring subscriptions"
        PlatformPermission.CONTENT_AGENTS_MANAGE -> "Read and change subject agent prompts"
        PlatformPermission.SECURITY_READ -> "Read scan logs, alerts, quarantine and posture"
        PlatformPermission.SECURITY_OPERATE -> "Change the upload security posture and resolve quarantined objects"
    }
}

/** A console role as the console reads it. */
data class ConsoleRoleView(
    val roleId: String,
    val name: String,
    val description: String,
    val capabilities: List<String>,
    val isSystem: Boolean,
)

/** One capability and what it means, for the role editor. */
data class CapabilityView(val name: String, val description: String)

/** An ADMIN account's console role and effective capabilities. */
data class ConsoleAccountView(
    val userId: String,
    val name: String,
    val phoneNumber: String,
    val roleName: String?,
    val capabilities: List<String>,
)
