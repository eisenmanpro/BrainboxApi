package com.afrithecus.brainbox.api.identity

/**
 * A capability on the platform console. This is the unit of authority: a console **role** is a
 * named set of capabilities, an account is assigned a role (plus optional per-account extras),
 * and every console route requires the capability for the action it performs.
 *
 * The ADMIN role gets an account through the door (`hasRole('ADMIN')`); a capability decides
 * what it may actually do there. So "admin" is not the same as "may suspend a teacher", "may
 * publish news" or "may disable upload scanning".
 *
 * Names are stable identifiers — they appear in stored role rows, in the console's UI and in
 * the audit trail, so renaming one is a migration, not a refactor.
 */
enum class PlatformPermission {

    // --- console plumbing -------------------------------------------------------------

    /** Read the console's own surfaces: who the caller is, the audit trail. */
    CONSOLE_READ,

    /** Create console roles and assign them; the only capability that can widen authority. */
    PLATFORM_ADMIN,

    // --- users ------------------------------------------------------------------------

    /** List and inspect accounts of every type. */
    USERS_READ,

    /** Suspend, unsuspend, verify, force a password reset, and create accounts. */
    USERS_MANAGE,

    // --- schools ----------------------------------------------------------------------

    /** List and inspect schools and their counts. */
    SCHOOLS_READ,

    /** Create and update schools, suspend and reactivate them. */
    SCHOOLS_MANAGE,

    // --- public content ---------------------------------------------------------------

    /** Write the public news feed: create, edit, publish, unpublish, delete. */
    NEWS_MANAGE,

    // --- messaging --------------------------------------------------------------------

    /** Compose notifications, target an audience, schedule and cancel sends. */
    NOTIFICATIONS_MANAGE,

    /**
     * Write messages into users' inboxes as Brainbox. Separate from notifications because an
     * inbox message looks like correspondence: it is attributable, visible in the message
     * centre, and can be replied to.
     */
    MESSAGES_MANAGE,

    /** Read the subscriber book: active, inactive and expiring subscriptions. */
    SUBSCRIBERS_READ,

    // --- content pipeline -------------------------------------------------------------

    /** Read and change the per-subject agent prompts that steer content generation. */
    CONTENT_AGENTS_MANAGE,

    // --- media security ---------------------------------------------------------------

    /** Read the scan log, alerts, quarantine and posture. */
    SECURITY_READ,

    /** Change the upload security posture and resolve quarantined objects. */
    SECURITY_OPERATE,
    ;

    companion object {

        /**
         * Reads the stored column (a role's or an account's capabilities). An unknown token is
         * ignored rather than raising: a stale row must never grant a capability that no
         * longer exists, and must never lock a valid one out.
         */
        fun parse(raw: String?): Set<PlatformPermission> {
            if (raw.isNullOrBlank()) return emptySet()
            return raw.split(',').mapNotNull { token ->
                val normalized = token.trim().uppercase().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                entries.firstOrNull { it.name == normalized }
            }.toSet()
        }

        /** The stored form of [permissions], in enum order and de-duplicated. */
        fun format(permissions: Set<PlatformPermission>): String =
            entries.filter { it in permissions }.joinToString(",") { it.name }

        /** True when the set satisfies [permission] ([PLATFORM_ADMIN] satisfies everything). */
        fun satisfies(permissions: Set<PlatformPermission>, permission: PlatformPermission): Boolean =
            permission in permissions || PLATFORM_ADMIN in permissions
    }
}
