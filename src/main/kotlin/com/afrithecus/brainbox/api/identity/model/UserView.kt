package com.afrithecus.brainbox.api.identity.model

import java.util.UUID

/**
 * Authenticated principal carried in the security context.
 * Derived from the access-token claims.
 */
data class CurrentUser(
    val userId: UUID,
    val role: Role,
    val subRole: SubRole? = null,
    val sessionId: UUID? = null,
    val deviceId: String? = null,
) {
    val authorities: Set<String>
        get() = buildSet {
            add("ROLE_" + role.name)
            subRole?.let { add("ROLE_" + it.name) }
        }
}
