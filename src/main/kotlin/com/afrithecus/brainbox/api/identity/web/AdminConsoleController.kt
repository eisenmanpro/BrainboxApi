package com.afrithecus.brainbox.api.identity.web

import com.afrithecus.brainbox.api.console.web.ConsoleIdentityPayload
import com.afrithecus.brainbox.api.identity.AuditLogService
import com.afrithecus.brainbox.api.identity.ConsoleActions
import com.afrithecus.brainbox.api.identity.PlatformAccessService
import com.afrithecus.brainbox.api.identity.model.CurrentUser
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

/**
 * The platform console's own surfaces: who the caller is and what they may do, plus the audit
 * trail.
 *
 * `/me` is what the console renders itself from. It returns the caller's capabilities **and the
 * actionable items those capabilities allow**, so an operator sees exactly their job and no
 * control the API would refuse. This is a UX contract, not a second security boundary: every
 * route re-checks the capability server-side.
 */
@RestController
@RequestMapping("/admin/console")
@PreAuthorize("hasRole(\'ADMIN\')")
class AdminConsoleController(
    private val access: PlatformAccessService,
    private val audit: AuditLogService,
) {

    /**
     * The operator's identity, capabilities and actionable items. Requires `CONSOLE_READ`, so an
     * administrator with no console capability gets a `403` here and therefore never sees a
     * console at all.
     */
    @GetMapping("/me")
    fun me(@AuthenticationPrincipal current: CurrentUser): ConsoleIdentityPayload {
        val user = access.requireRead(current)
        val capabilities = access.capabilitiesOf(user)
        return ConsoleIdentityPayload(
            userId = user.id.toString(),
            name = user.name,
            role = user.role.name,
            consoleRoleName = user.consoleRoleId?.let { id -> access.roleName(id) },
            capabilities = capabilities.map { it.name }.sorted(),
            actions = ConsoleActions.forCapabilities(capabilities),
        )
    }

    /** Platform (school-less) audit entries, newest first. */
    @GetMapping("/audit")
    fun audit(
        @AuthenticationPrincipal current: CurrentUser,
        @RequestParam(required = false, defaultValue = "50") limit: Int,
        @RequestParam(required = false) before: Long?,
    ): List<AuditLogEntryPayload> {
        access.requireRead(current)
        return audit.listPlatform(limit, before?.let(Instant::ofEpochMilli))
    }
}
