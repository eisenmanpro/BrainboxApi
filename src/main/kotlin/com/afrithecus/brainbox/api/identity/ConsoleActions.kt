package com.afrithecus.brainbox.api.identity

import com.afrithecus.brainbox.api.console.web.ConsoleAction

/**
 * The actionable items a capability grants.
 *
 * This is the console's menu: `/admin/console/me` returns the actions the caller's capabilities
 * allow and nothing else, so an operator is never shown a control the API would refuse. The ids
 * are stable — the console maps them to screens — and the API's own authorization remains the
 * real gate; this list is what makes the console *honest* about what the caller can do rather
 * than a second security boundary.
 */
object ConsoleActions {

    private val BY_CAPABILITY: Map<PlatformPermission, List<ConsoleAction>> = mapOf(
        PlatformPermission.CONSOLE_READ to listOf(
            action("console.audit.view", "View the platform audit trail", PlatformPermission.CONSOLE_READ, "READ"),
        ),
        PlatformPermission.PLATFORM_ADMIN to listOf(
            action("console.roles.manage", "Manage console roles and capabilities", PlatformPermission.PLATFORM_ADMIN, "WRITE"),
            action("console.operators.assign", "Assign console roles to administrators", PlatformPermission.PLATFORM_ADMIN, "WRITE"),
            action("console.capabilities.grant", "Grant capabilities to an account", PlatformPermission.PLATFORM_ADMIN, "WRITE"),
        ),
        PlatformPermission.USERS_READ to listOf(
            action("users.list", "List and filter accounts of every type", PlatformPermission.USERS_READ, "READ"),
            action("users.detail", "Inspect one account", PlatformPermission.USERS_READ, "READ"),
            action("users.counts", "See account totals by type", PlatformPermission.USERS_READ, "READ"),
        ),
        PlatformPermission.USERS_MANAGE to listOf(
            action("users.create", "Create an account with generated credentials", PlatformPermission.USERS_MANAGE, "WRITE"),
            action("users.suspend", "Suspend or restore an account", PlatformPermission.USERS_MANAGE, "WRITE"),
            action("users.verify", "Verify or unverify an account", PlatformPermission.USERS_MANAGE, "WRITE"),
            action("users.reset-password", "Issue a temporary password", PlatformPermission.USERS_MANAGE, "WRITE"),
        ),
        PlatformPermission.SCHOOLS_READ to listOf(
            action("schools.list", "List and inspect schools", PlatformPermission.SCHOOLS_READ, "READ"),
            action("schools.counts", "See school totals", PlatformPermission.SCHOOLS_READ, "READ"),
        ),
        PlatformPermission.SCHOOLS_MANAGE to listOf(
            action("schools.create", "Create a school", PlatformPermission.SCHOOLS_MANAGE, "WRITE"),
            action("schools.update", "Edit a school", PlatformPermission.SCHOOLS_MANAGE, "WRITE"),
            action("schools.suspend", "Suspend or reactivate a school", PlatformPermission.SCHOOLS_MANAGE, "WRITE"),
        ),
        PlatformPermission.NEWS_MANAGE to listOf(
            action("news.list", "Read the news feed including drafts", PlatformPermission.NEWS_MANAGE, "READ"),
            action("news.create", "Write a news article", PlatformPermission.NEWS_MANAGE, "WRITE"),
            action("news.update", "Edit a news article", PlatformPermission.NEWS_MANAGE, "WRITE"),
            action("news.publish", "Publish or unpublish an article", PlatformPermission.NEWS_MANAGE, "WRITE"),
            action("news.delete", "Delete an article", PlatformPermission.NEWS_MANAGE, "WRITE"),
        ),
        PlatformPermission.NOTIFICATIONS_MANAGE to listOf(
            action("notifications.compose", "Compose and target a notification", PlatformPermission.NOTIFICATIONS_MANAGE, "WRITE"),
            action("notifications.schedule", "Schedule or cancel a send", PlatformPermission.NOTIFICATIONS_MANAGE, "WRITE"),
            action("notifications.automation", "Manage automation rules", PlatformPermission.NOTIFICATIONS_MANAGE, "WRITE"),
        ),
        PlatformPermission.MESSAGES_MANAGE to listOf(
            action("messages.compose", "Write a message into users' inboxes", PlatformPermission.MESSAGES_MANAGE, "WRITE"),
            action("messages.history", "Read what Brainbox has sent", PlatformPermission.MESSAGES_MANAGE, "READ"),
            action("messages.replies", "Read and clear what users wrote back", PlatformPermission.MESSAGES_MANAGE, "READ"),
        ),
        PlatformPermission.SUBSCRIBERS_READ to listOf(
            action("subscribers.list", "View active, inactive and expiring subscribers", PlatformPermission.SUBSCRIBERS_READ, "READ"),
            action("subscribers.counts", "See subscription totals", PlatformPermission.SUBSCRIBERS_READ, "READ"),
        ),
        PlatformPermission.CONTENT_AGENTS_MANAGE to listOf(
            action("agents.prompts.view", "Read the subject agent prompts", PlatformPermission.CONTENT_AGENTS_MANAGE, "READ"),
            action("agents.prompts.update", "Edit a subject agent prompt", PlatformPermission.CONTENT_AGENTS_MANAGE, "WRITE"),
            action("agents.prompts.reset", "Revert a prompt to the seeded default", PlatformPermission.CONTENT_AGENTS_MANAGE, "WRITE"),
        ),
        PlatformPermission.SECURITY_READ to listOf(
            action("security.posture.view", "Read the upload security posture", PlatformPermission.SECURITY_READ, "READ"),
            action("security.scans.view", "Read the scan log", PlatformPermission.SECURITY_READ, "READ"),
            action("security.alerts.view", "Read the alert queue", PlatformPermission.SECURITY_READ, "READ"),
            action("security.quarantine.view", "Inspect quarantined objects", PlatformPermission.SECURITY_READ, "READ"),
            action("security.url-check", "Check a URL's reputation", PlatformPermission.SECURITY_READ, "READ"),
        ),
        PlatformPermission.SECURITY_OPERATE to listOf(
            action("security.posture.update", "Change the upload security posture", PlatformPermission.SECURITY_OPERATE, "WRITE"),
            action("security.alerts.ack", "Acknowledge an alert", PlatformPermission.SECURITY_OPERATE, "WRITE"),
            action("security.quarantine.restore", "Restore a quarantined object", PlatformPermission.SECURITY_OPERATE, "WRITE"),
            action("security.quarantine.delete", "Destroy a quarantined object", PlatformPermission.SECURITY_OPERATE, "WRITE"),
        ),
    )

    /** Every action [capabilities] allow, de-duplicated and stably ordered. */
    fun forCapabilities(capabilities: Set<PlatformPermission>): List<ConsoleAction> {
        // PLATFORM_ADMIN satisfies every capability, so it must widen the *action* list too —
        // otherwise the owner would see a console with three items in it.
        val effective = if (PlatformPermission.PLATFORM_ADMIN in capabilities) {
            PlatformPermission.entries.toSet()
        } else {
            capabilities
        }
        return effective
            .flatMap { capability -> BY_CAPABILITY[capability].orEmpty() }
            // PLATFORM_ADMIN implies other capabilities, so the same action can arrive twice.
            .distinctBy { it.id }
            .sortedBy { it.id }
    }

    /** The full catalogue, for documentation and the role editor's help text. */
    fun all(): List<ConsoleAction> = PlatformPermission.entries.flatMap { BY_CAPABILITY[it].orEmpty() }

    private fun action(
        id: String,
        label: String,
        capability: PlatformPermission,
        kind: String,
    ) = ConsoleAction(id = id, label = label, capability = capability.name, kind = kind)
}
