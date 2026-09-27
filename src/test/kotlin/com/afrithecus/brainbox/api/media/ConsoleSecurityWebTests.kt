package com.afrithecus.brainbox.api.media

import com.afrithecus.brainbox.api.auth.web.AuthResponse
import com.afrithecus.brainbox.api.identity.PlatformPermission
import com.afrithecus.brainbox.api.identity.PlatformOperator
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.model.CurrentUser
import com.afrithecus.brainbox.api.identity.model.Role
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import com.afrithecus.brainbox.api.media.security.web.MediaSecuritySettingsPayload
import com.afrithecus.brainbox.api.media.security.web.PlatformOperatorPayload
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper

/**
 * Console authorization. The ADMIN role gets an account through the door; a platform
 * permission decides what it may actually do — so an ordinary administrator cannot read the
 * scan log, change the upload security posture, resolve a quarantined object, or hand out
 * permissions. Every operator action is audited from the server's own record.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ConsoleSecurityWebTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val users: UserRepository,
    @Autowired private val passwordEncoder: PasswordEncoder,
    @Autowired private val access: com.afrithecus.brainbox.api.identity.PlatformAccessService,
    @Autowired private val audit: com.afrithecus.brainbox.api.identity.AuditLogService,
    @Autowired private val roleRepository: com.afrithecus.brainbox.api.identity.repository.ConsoleRoleRepository,
) {

    @Test
    fun `an admin without a permission can neither read nor operate`() {
        val plainAdmin = account("0755070001", Role.ADMIN)
        val token = token(plainAdmin)

        // Read surfaces are gated too: the scan log names object keys and owners.
        mockMvc.perform(get("/admin/media/security/summary").header("Authorization", auth(token)))
            .andExpect(status().isForbidden)
        mockMvc.perform(get("/admin/media/security/scans").header("Authorization", auth(token)))
            .andExpect(status().isForbidden)
        mockMvc.perform(get("/admin/media/security/alerts").header("Authorization", auth(token)))
            .andExpect(status().isForbidden)
        mockMvc.perform(get("/admin/media/security/quarantine").header("Authorization", auth(token)))
            .andExpect(status().isForbidden)

        // ... and so is every mutation, including the destructive ones.
        mockMvc.perform(
            put("/admin/media/security/settings").header("Authorization", auth(token))
                .contentType(MediaType.APPLICATION_JSON).content("""{"scanEnabled":false}""")
        ).andExpect(status().isForbidden)
        mockMvc.perform(
            post("/admin/media/security/url-check").header("Authorization", auth(token))
                .contentType(MediaType.APPLICATION_JSON).content("""{"url":"https://example.org/a.png"}""")
        ).andExpect(status().isForbidden)

        // A non-admin cannot even reach the surface, whatever the permissions would say.
        val teacher = account("0755070002", Role.TEACHER)
        mockMvc.perform(get("/admin/media/security/summary").header("Authorization", auth(token(teacher))))
            .andExpect(status().isForbidden)
        mockMvc.perform(get("/admin/media/security/summary")).andExpect(status().isUnauthorized)
    }

    @Test
    fun `CONSOLE_READ can read the console but not the security surfaces or any write`() {
        // CONSOLE_READ alone is the console's own surface (identity, audit) — the security reads
        // are a separate capability, so an operator can be given the console without the log.
        val consoleOnly = account("0755070020", Role.ADMIN, permissions = listOf(PlatformPermission.CONSOLE_READ))
        mockMvc.perform(get("/admin/media/security/summary").header("Authorization", auth(token(consoleOnly))))
            .andExpect(status().isForbidden)
        mockMvc.perform(get("/admin/console/me").header("Authorization", auth(token(consoleOnly))))
            .andExpect(status().isOk)

        val reader = account(
            "0755070003",
            Role.ADMIN,
            permissions = listOf(PlatformPermission.CONSOLE_READ, PlatformPermission.SECURITY_READ),
        )
        val token = token(reader)

        // Reads work...
        val summary = objectMapper.readValue(
            mockMvc.perform(get("/admin/media/security/summary").header("Authorization", auth(token)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            com.afrithecus.brainbox.api.media.security.web.MediaSecuritySummaryPayload::class.java,
        )
        check(summary.scansByStatus.containsKey("SKIPPED"))

        // ... and writes do not, not even the posture read-through.
        mockMvc.perform(
            put("/admin/media/security/settings").header("Authorization", auth(token))
                .contentType(MediaType.APPLICATION_JSON).content("""{"onInfection":"DELETE"}""")
        ).andExpect(status().isForbidden)
        mockMvc.perform(
            post("/admin/media/security/alerts/${java.util.UUID.randomUUID()}/ack")
                .header("Authorization", auth(token))
        ).andExpect(status().isForbidden)
        mockMvc.perform(
            delete("/admin/media/security/quarantine/${java.util.UUID.randomUUID()}")
                .header("Authorization", auth(token))
        ).andExpect(status().isForbidden)
    }

    @Test
    fun `SECURITY_OPERATE can change the posture and every action is audited`() {
        val operator = account("0755070004", Role.ADMIN, permissions = listOf(PlatformPermission.SECURITY_OPERATE))
        val token = token(operator)

        // Reads are still gated: operate does not imply read.
        mockMvc.perform(get("/admin/media/security/summary").header("Authorization", auth(token)))
            .andExpect(status().isForbidden)

        val updated = objectMapper.readValue(
            mockMvc.perform(
                put("/admin/media/security/settings").header("Authorization", auth(token))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"scanProvider":"clamav","onInfection":"DELETE"}""")
            ).andExpect(status().isOk).andReturn().response.contentAsString,
            MediaSecuritySettingsPayload::class.java,
        )
        check(updated.scanProvider == "clamav" && updated.onInfection == "DELETE")

        // The server's audit trail records the actor, the target and what actually moved.
        val entries = audit.listPlatform(20, null)
        val entry = entries.firstOrNull { it.action == "media_security_settings_updated" }
        check(entry != null) { "a posture change must be audited, saw " + entries.map { it.action } }
        check(entry!!.actorName == operator.name)
    }

    @Test
    fun `PLATFORM_ADMIN manages operators, and only it can`() {
        val owner = account("0755070005", Role.ADMIN, permissions = listOf(PlatformPermission.PLATFORM_ADMIN))
        val operateOnly = account("0755070006", Role.ADMIN, permissions = listOf(PlatformPermission.SECURITY_OPERATE))
        val candidate = account("0755070007", Role.ADMIN)
        val ownerToken = token(owner)
        val operateToken = token(operateOnly)

        // An operator without PLATFORM_ADMIN cannot hand out permissions.
        mockMvc.perform(get("/admin/media/security/operators").header("Authorization", auth(operateToken)))
            .andExpect(status().isForbidden)
        mockMvc.perform(
            post("/admin/media/security/operators/${candidate.id}/permissions")
                .header("Authorization", auth(operateToken))
                .contentType(MediaType.APPLICATION_JSON).content("""{"permissions":["CONSOLE_READ"]}""")
        ).andExpect(status().isForbidden)

        // The operator manager grants and revokes, and both are audited.
        val granted = objectMapper.readValue(
            mockMvc.perform(
                post("/admin/media/security/operators/${candidate.id}/permissions")
                    .header("Authorization", auth(ownerToken))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"permissions":["CONSOLE_READ","SECURITY_OPERATE"]}""")
            ).andExpect(status().isOk).andReturn().response.contentAsString,
            PlatformOperatorPayload::class.java,
        )
        check(granted.permissions == listOf("CONSOLE_READ", "SECURITY_OPERATE")) { granted.permissions.toString() }
        check(!granted.platformAdmin)
        check(!granted.unprivileged)

        val revoked = objectMapper.readValue(
            mockMvc.perform(
                delete("/admin/media/security/operators/${candidate.id}/permissions")
                    .header("Authorization", auth(ownerToken))
                    .contentType(MediaType.APPLICATION_JSON).content("""{"permissions":["SECURITY_OPERATE"]}""")
            ).andExpect(status().isOk).andReturn().response.contentAsString,
            PlatformOperatorPayload::class.java,
        )
        check(revoked.permissions == listOf("CONSOLE_READ"))

        // Unknown permission names and non-admin targets are refused, not silently ignored.
        mockMvc.perform(
            post("/admin/media/security/operators/${candidate.id}/permissions")
                .header("Authorization", auth(ownerToken))
                .contentType(MediaType.APPLICATION_JSON).content("""{"permissions":["SUPER_USER"]}""")
        ).andExpect(status().isBadRequest)
        val teacher = account("0755070008", Role.TEACHER)
        mockMvc.perform(
            post("/admin/media/security/operators/${teacher.id}/permissions")
                .header("Authorization", auth(ownerToken))
                .contentType(MediaType.APPLICATION_JSON).content("""{"permissions":["CONSOLE_READ"]}""")
        ).andExpect(status().isBadRequest)

        val auditActions = audit.listPlatform(50, null).map { it.action }
        check(auditActions.contains("platform_permissions_granted")) { auditActions.toString() }
        check(auditActions.contains("platform_permissions_revoked")) { auditActions.toString() }
    }

    /**
     * An operator cannot strand the console: removing your own PLATFORM_ADMIN while nobody else
     * holds it is refused, which is the lockout the rule exists to prevent.
     */
    @Test
    fun `the last platform admin cannot revoke themselves`() {
        val owner = account("0755070009", Role.ADMIN, permissions = listOf(PlatformPermission.PLATFORM_ADMIN))
        mockMvc.perform(
            delete("/admin/media/security/operators/${owner.id}/permissions")
                .header("Authorization", auth(token(owner)))
                .contentType(MediaType.APPLICATION_JSON).content("""{"permissions":["PLATFORM_ADMIN"]}""")
        ).andExpect(status().isBadRequest)

        // With a second platform admin it is allowed.
        account("0755070010", Role.ADMIN, permissions = listOf(PlatformPermission.PLATFORM_ADMIN))
        mockMvc.perform(
            delete("/admin/media/security/operators/${owner.id}/permissions")
                .header("Authorization", auth(token(owner)))
                .contentType(MediaType.APPLICATION_JSON).content("""{"permissions":["PLATFORM_ADMIN"]}""")
        ).andExpect(status().isOk)
    }

    /** An unknown stored token grants nothing; a valid one is still honoured. */
    @Test
    fun `a malformed permission column neither grants nor crashes`() {
        val weird = account("0755070011", Role.ADMIN)
        weird.platformPermissions = "SUPER_USER, SECURITY_READ ,,"
        users.save(weird)
        check(access.permissionsOf(weird) == setOf(PlatformPermission.SECURITY_READ)) {
            "an unknown token must be ignored and the valid one honoured"
        }

        val token = token(weird)
        mockMvc.perform(get("/admin/media/security/summary").header("Authorization", auth(token)))
            .andExpect(status().isOk)
        mockMvc.perform(
            put("/admin/media/security/settings").header("Authorization", auth(token))
                .contentType(MediaType.APPLICATION_JSON).content("""{"scanEnabled":true}""")
        ).andExpect(status().isForbidden)
    }

    /** The bootstrap path grants PLATFORM_ADMIN to a configured ADMIN phone, once. */
    @Test
    fun `bootstrap grants the configured operator and is idempotent`() {
        val configured = account("0755070012", Role.ADMIN)
        val bootstrap = com.afrithecus.brainbox.api.identity.PlatformAccessService(
            users = users,
            audit = audit,
            roles = roleRepository,
            bootstrapPhones = "0755070012,0755000000",
        )
        bootstrap.bootstrapOperators()
        check(access.has(users.findById(configured.id).orElseThrow(), PlatformPermission.PLATFORM_ADMIN))

        // A second run does not duplicate the audit entry or change anything.
        val before = audit.listPlatform(50, null).size
        bootstrap.bootstrapOperators()
        check(audit.listPlatform(50, null).size == before) { "bootstrap must be idempotent" }

        // A non-admin phone is skipped rather than escalated.
        val teacher = account("0755070013", Role.TEACHER)
        val teacherBootstrap = com.afrithecus.brainbox.api.identity.PlatformAccessService(
            users = users,
            audit = audit,
            roles = roleRepository,
            bootstrapPhones = "0755070013",
        )
        teacherBootstrap.bootstrapOperators()
        check(!access.has(users.findById(teacher.id).orElseThrow(), PlatformPermission.PLATFORM_ADMIN))
    }

    // ---------------------------------------------------------------- helpers

    private fun account(
        phone: String,
        role: Role,
        permissions: List<PlatformPermission> = emptyList(),
    ): UserEntity = users.save(
        UserEntity().apply {
            phoneNumber = phone
            email = phone + "@consoleguard.test"
            passwordHash = passwordEncoder.encode("password123") ?: error("encode")
            name = "Console " + role.name + " " + phone.takeLast(4)
            this.role = role
            isActive = true
            isVerified = true
            platformPermissions = permissions.takeIf { it.isNotEmpty() }?.let { PlatformPermission.format(it.toSet()) }
        }
    )

    private fun token(user: UserEntity): String {
        val body = mockMvc.perform(
            post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("""{"identifier":"${user.phoneNumber}","password":"password123"}""")
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return objectMapper.readValue(body, AuthResponse::class.java).sessionToken!!
    }

    private fun auth(token: String) = "Bearer " + token

    @Suppress("unused")
    private fun currentOf(user: UserEntity) = CurrentUser(user.id, user.role, user.subRole)

    @Suppress("unused")
    private fun operatorRow(user: UserEntity): PlatformOperator =
        PlatformOperator(user, access.permissionsOf(user))
}
