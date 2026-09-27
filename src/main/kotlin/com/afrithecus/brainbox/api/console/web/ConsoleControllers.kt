package com.afrithecus.brainbox.api.console.web

import com.afrithecus.brainbox.api.console.ConsoleSchoolService
import com.afrithecus.brainbox.api.console.ConsoleSubscriberService
import com.afrithecus.brainbox.api.console.ConsoleUserService
import com.afrithecus.brainbox.api.identity.PlatformPermission
import com.afrithecus.brainbox.api.identity.model.CurrentUser
import com.afrithecus.brainbox.api.identity.model.Role
import com.afrithecus.brainbox.api.identity.model.SubRole
import jakarta.validation.Valid
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Platform user administration: see and act on **any** account — learners, teachers (including
 * ICT admins and grade coordinators), parents and other admins.
 *
 * `hasRole('ADMIN')` gets an account through the door; `USERS_READ` and `USERS_MANAGE` decide
 * what it may do. Every action is audited with the actor, and the generated credential is
 * returned exactly once.
 */
@RestController
@RequestMapping("/admin/console/users")
@PreAuthorize("hasRole('ADMIN')")
class ConsoleUserController(private val service: ConsoleUserService) {

    @GetMapping
    fun list(
        @AuthenticationPrincipal current: CurrentUser,
        @RequestParam(required = false) role: String?,
        @RequestParam(required = false) subRole: String?,
        @RequestParam(required = false) status: String?,
        @RequestParam(required = false) schoolId: String?,
        @RequestParam(required = false) q: String?,
        @RequestParam(required = false, defaultValue = "50") limit: Int,
        @RequestParam(required = false) before: Long?,
    ): List<ConsoleUserView> = service.list(
        current = current,
        role = role?.trim()?.takeIf { it.isNotEmpty() }?.let { parseRole(it) },
        subRole = subRole?.trim()?.takeIf { it.isNotEmpty() }?.let { parseSubRole(it) },
        status = status,
        schoolId = schoolId?.trim()?.takeIf { it.isNotEmpty() }?.let { parseUuid(it, "schoolId") },
        query = q,
        limit = limit,
        before = before,
    )

    @GetMapping("/counts")
    fun counts(@AuthenticationPrincipal current: CurrentUser): Map<String, Long> = service.counts(current)

    @GetMapping("/{userId}")
    fun get(
        @AuthenticationPrincipal current: CurrentUser,
        @PathVariable userId: String,
    ): ConsoleUserView = service.get(current, userId)

    /**
     * Override creation: any type, generated credentials, optional verification. The response's
     * `credential.temporaryPassword` is the only time the password is readable.
     */
    @PostMapping
    fun create(
        @AuthenticationPrincipal current: CurrentUser,
        @Valid @RequestBody request: ConsoleCreateUserRequest,
    ): ConsoleCreatedUser = service.create(current, request)

    @PostMapping("/{userId}/active")
    fun setActive(
        @AuthenticationPrincipal current: CurrentUser,
        @PathVariable userId: String,
        @RequestBody request: ConsoleSetActiveRequest,
    ): ConsoleUserView = service.setActive(current, userId, request.active)

    @PostMapping("/{userId}/verified")
    fun setVerified(
        @AuthenticationPrincipal current: CurrentUser,
        @PathVariable userId: String,
        @RequestBody request: ConsoleSetVerifiedRequest,
    ): ConsoleUserView = service.setVerified(current, userId, request.verified)

    /** Issues a temporary password; returned once, audited. */
    @PostMapping("/{userId}/password-reset")
    fun resetPassword(
        @AuthenticationPrincipal current: CurrentUser,
        @PathVariable userId: String,
    ): ConsoleCredential = service.resetPassword(current, userId)

    private fun parseRole(raw: String): Role =
        runCatching { Role.valueOf(raw.uppercase()) }.getOrNull()
            ?: throw com.afrithecus.brainbox.api.common.error.invalidArgument(
                "role must be STUDENT, TEACHER, PARENT or ADMIN",
            )

    private fun parseSubRole(raw: String): SubRole =
        runCatching { SubRole.valueOf(raw.uppercase()) }.getOrNull()
            ?: throw com.afrithecus.brainbox.api.common.error.invalidArgument(
                "subRole must be CTEACHER, GRADE_COORDINATOR or ICT_ADMIN",
            )

    private fun parseUuid(raw: String, field: String): UUID =
        runCatching { UUID.fromString(raw) }.getOrNull()
            ?: throw com.afrithecus.brainbox.api.common.error.invalidArgument("$field is not a valid identifier")
}

/** Platform school administration, including the switch that hides a school publicly. */
@RestController
@RequestMapping("/admin/console/schools")
@PreAuthorize("hasRole('ADMIN')")
class ConsoleSchoolController(private val service: ConsoleSchoolService) {

    @GetMapping
    fun list(
        @AuthenticationPrincipal current: CurrentUser,
        @RequestParam(required = false) active: Boolean?,
        @RequestParam(required = false) q: String?,
        @RequestParam(required = false, defaultValue = "100") limit: Int,
    ): List<ConsoleSchoolView> = service.list(current, active, q, limit)

    @GetMapping("/counts")
    fun counts(@AuthenticationPrincipal current: CurrentUser): Map<String, Long> = service.counts(current)

    @PostMapping
    fun create(
        @AuthenticationPrincipal current: CurrentUser,
        @Valid @RequestBody request: ConsoleCreateSchoolRequest,
    ): ConsoleSchoolView = service.create(current, request)

    @PostMapping("/{schoolId}")
    fun update(
        @AuthenticationPrincipal current: CurrentUser,
        @PathVariable schoolId: String,
        @Valid @RequestBody request: ConsoleCreateSchoolRequest,
    ): ConsoleSchoolView = service.update(current, schoolId, request)

    /** Suspends or reactivates a school; a suspended school leaves the public directory. */
    @PostMapping("/{schoolId}/active")
    fun setActive(
        @AuthenticationPrincipal current: CurrentUser,
        @PathVariable schoolId: String,
        @RequestBody request: ConsoleSetActiveRequest,
    ): ConsoleSchoolView = service.setActive(current, schoolId, request.active)
}

/** The subscriber book: active, inactive, expiring. */
@RestController
@RequestMapping("/admin/console/subscribers")
@PreAuthorize("hasRole('ADMIN')")
class ConsoleSubscriberController(private val service: ConsoleSubscriberService) {

    @GetMapping
    fun list(
        @AuthenticationPrincipal current: CurrentUser,
        @RequestParam(required = false) status: String?,
        @RequestParam(required = false) tier: String?,
        @RequestParam(required = false) schoolId: String?,
        @RequestParam(required = false) activeOnly: Boolean?,
        @RequestParam(required = false, defaultValue = "100") limit: Int,
    ): List<ConsoleSubscriberView> = service.list(
        current = current,
        status = status,
        tier = tier,
        schoolId = schoolId?.trim()?.takeIf { it.isNotEmpty() }?.let {
            runCatching { UUID.fromString(it) }.getOrNull()
                ?: throw com.afrithecus.brainbox.api.common.error.invalidArgument(
                    "schoolId is not a valid identifier",
                )
        },
        activeOnly = activeOnly,
        limit = limit,
    )

    @GetMapping("/counts")
    fun counts(@AuthenticationPrincipal current: CurrentUser): Map<String, Long> = service.counts(current)
}

/** Notification composition, audience preview, scheduling and automation rules. */
@RestController
@RequestMapping("/admin/console/notifications")
@PreAuthorize("hasRole('ADMIN')")
class ConsoleNotificationController(
    private val service: com.afrithecus.brainbox.api.console.ConsoleNotificationService,
) {

    @GetMapping
    fun list(
        @AuthenticationPrincipal current: CurrentUser,
        @RequestParam(required = false) status: String?,
        @RequestParam(required = false, defaultValue = "50") limit: Int,
    ): List<ConsoleNotificationView> = service.list(current, status, limit)

    /** Who a send would reach right now; the audience is resolved again at send time. */
    @GetMapping("/audience")
    fun previewAudience(
        @AuthenticationPrincipal current: CurrentUser,
        @RequestParam audienceType: String,
        @RequestParam(required = false) audienceValue: String?,
    ): ConsoleAudiencePreview = service.previewAudience(current, audienceType, audienceValue)

    @PostMapping
    fun send(
        @AuthenticationPrincipal current: CurrentUser,
        @Valid @RequestBody request: ConsoleSendRequest,
    ): ConsoleNotificationView = service.send(current, request)

    @PostMapping("/{notificationId}/cancel")
    fun cancel(
        @AuthenticationPrincipal current: CurrentUser,
        @PathVariable notificationId: String,
    ): ConsoleNotificationView = service.cancel(current, notificationId)

    @GetMapping("/rules")
    fun rules(@AuthenticationPrincipal current: CurrentUser): List<ConsoleAutomationRuleView> = service.rules(current)

    @PostMapping("/rules")
    fun createRule(
        @AuthenticationPrincipal current: CurrentUser,
        @Valid @RequestBody request: ConsoleAutomationRuleRequest,
    ): ConsoleAutomationRuleView = service.createRule(current, request)

    @PostMapping("/rules/{ruleId}/enabled")
    fun setRuleEnabled(
        @AuthenticationPrincipal current: CurrentUser,
        @PathVariable ruleId: String,
        @RequestBody request: ConsoleSetActiveRequest,
    ): ConsoleAutomationRuleView = service.setRuleEnabled(current, ruleId, request.active)

    @DeleteMapping("/rules/{ruleId}")
    fun deleteRule(
        @AuthenticationPrincipal current: CurrentUser,
        @PathVariable ruleId: String,
    ): Map<String, String> = service.deleteRule(current, ruleId)
}

/**
 * Direct messages to users. These are written as **Brainbox**, the platform account, so they land
 * in the recipient's normal message centre and can be replied to. They are separate from
 * notifications on purpose: a notification is a push + in-app alert, a message is correspondence.
 */
@RestController
@RequestMapping("/admin/console/messages")
@PreAuthorize("hasRole('ADMIN')")
class ConsoleMessageController(
    private val service: com.afrithecus.brainbox.api.console.ConsoleNotificationService,
) {

    @GetMapping
    fun history(
        @AuthenticationPrincipal current: CurrentUser,
        @RequestParam(required = false) status: String?,
        @RequestParam(required = false, defaultValue = "50") limit: Int,
    ): List<ConsoleNotificationView> = service.list(
        current = current,
        status = status,
        limit = limit,
        channel = com.afrithecus.brainbox.api.console.ConsoleNotificationService.CHANNEL_MESSAGE,
        capability = PlatformPermission.MESSAGES_MANAGE,
    )

    @GetMapping("/audience")
    fun previewAudience(
        @AuthenticationPrincipal current: CurrentUser,
        @RequestParam audienceType: String,
        @RequestParam(required = false) audienceValue: String?,
    ): ConsoleAudiencePreview = service.previewAudience(
        current = current,
        audienceType = audienceType,
        audienceValue = audienceValue,
        capability = PlatformPermission.MESSAGES_MANAGE,
    )

    /** The body becomes the message body; the title becomes the subject. */
    @PostMapping
    fun send(
        @AuthenticationPrincipal current: CurrentUser,
        @Valid @RequestBody request: ConsoleSendRequest,
    ): ConsoleNotificationView = service.sendMessage(current, request)

    @PostMapping("/{messageId}/cancel")
    fun cancel(
        @AuthenticationPrincipal current: CurrentUser,
        @PathVariable messageId: String,
    ): ConsoleNotificationView = service.cancel(
        current = current,
        notificationId = messageId,
        capability = PlatformPermission.MESSAGES_MANAGE,
        channel = com.afrithecus.brainbox.api.console.ConsoleNotificationService.CHANNEL_MESSAGE,
    )

    /** What users wrote back to Brainbox, oldest page first is not needed: newest first. */
    @GetMapping("/replies")
    fun replies(
        @AuthenticationPrincipal current: CurrentUser,
        @RequestParam(required = false, defaultValue = "50") limit: Int,
    ): List<com.afrithecus.brainbox.api.messaging.web.MessagePayload> = service.replies(current, limit)

    @PostMapping("/replies/{messageId}/read")
    fun markReplyRead(
        @AuthenticationPrincipal current: CurrentUser,
        @PathVariable messageId: String,
    ): com.afrithecus.brainbox.api.messaging.web.MessagePayload =
        service.markReplyRead(current, messageId)
}

/** Console role management: roles, capabilities and assignment. */
@RestController
@RequestMapping("/admin/console/roles")
@PreAuthorize("hasRole('ADMIN')")
class ConsoleRoleController(
    private val roles: com.afrithecus.brainbox.api.identity.ConsoleRoleService,
) {

    @GetMapping
    fun list(@AuthenticationPrincipal current: CurrentUser): List<com.afrithecus.brainbox.api.identity.ConsoleRoleView> =
        roles.list(current)

    /** The capability catalogue, so the role editor needs no hardcoded list. */
    @GetMapping("/capabilities")
    fun capabilities(
        @AuthenticationPrincipal current: CurrentUser,
    ): List<com.afrithecus.brainbox.api.identity.CapabilityView> = roles.capabilities(current)

    @GetMapping("/accounts")
    fun accounts(
        @AuthenticationPrincipal current: CurrentUser,
    ): List<com.afrithecus.brainbox.api.identity.ConsoleAccountView> = roles.accounts(current)

    @PostMapping
    fun create(
        @AuthenticationPrincipal current: CurrentUser,
        @Valid @RequestBody request: ConsoleRoleRequest,
    ): com.afrithecus.brainbox.api.identity.ConsoleRoleView =
        roles.create(current, request.name, request.description, request.capabilities)

    @PostMapping("/{roleId}")
    fun update(
        @AuthenticationPrincipal current: CurrentUser,
        @PathVariable roleId: String,
        @RequestBody request: ConsoleRoleRequest,
    ): com.afrithecus.brainbox.api.identity.ConsoleRoleView =
        roles.update(current, roleId, request.description, request.capabilities, request.name)

    @DeleteMapping("/{roleId}")
    fun delete(
        @AuthenticationPrincipal current: CurrentUser,
        @PathVariable roleId: String,
    ): Map<String, String> = roles.delete(current, roleId)

    /** Assigns (or clears, with a null roleId) an ADMIN account's console role. */
    @PostMapping("/accounts/{userId}")
    fun assign(
        @AuthenticationPrincipal current: CurrentUser,
        @PathVariable userId: String,
        @RequestBody request: ConsoleRoleAssignRequest,
    ): com.afrithecus.brainbox.api.identity.ConsoleAccountView =
        roles.assign(current, userId, request.roleId)
}

data class ConsoleRoleRequest(
    val name: String = "",
    val description: String = "",
    val capabilities: List<String> = emptyList(),
)

data class ConsoleRoleAssignRequest(val roleId: String? = null)

/** Subject agent prompts: read the effective text, write an override. */
@RestController
@RequestMapping("/admin/console/agents")
@PreAuthorize("hasRole('ADMIN')")
class ConsoleAgentController(
    private val service: com.afrithecus.brainbox.api.console.SubjectAgentPromptService,
) {

    @GetMapping
    fun list(@AuthenticationPrincipal current: CurrentUser): List<SubjectAgentPromptView> = service.list(current)

    @PostMapping("/{agentCode}")
    fun update(
        @AuthenticationPrincipal current: CurrentUser,
        @PathVariable agentCode: String,
        @Valid @RequestBody request: SubjectAgentPromptRequest,
    ): SubjectAgentPromptView = service.update(current, agentCode, request)

    /** Removes the override so the agent falls back to the seeded default. */
    @DeleteMapping("/{agentCode}")
    fun reset(
        @AuthenticationPrincipal current: CurrentUser,
        @PathVariable agentCode: String,
    ): SubjectAgentPromptView = service.reset(current, agentCode)
}
