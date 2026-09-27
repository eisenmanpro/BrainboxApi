package com.afrithecus.brainbox.api.common.state

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/** What a feature does when the shared backend cannot answer. */
enum class FailureMode { ALLOW, DENY }

/**
 * The failure mode per feature, chosen for product usability rather than uniformity:
 *
 * | Feature | Default | Why |
 * | --- | --- | --- |
 * | Rate limiting | `ALLOW` | A Redis outage must not lock every user out of the product. The in-process store keeps counting, so limits degrade to per-node instead of disappearing. |
 * | Presence | `ALLOW` | Presence is advisory (a live-class count); an outage must not eject participants. |
 * | Revocation | `DENY` | Security: if the revocation list cannot be read, deny rather than accept a token that may have been revoked. Currently revocation is enforced from the session row in the database, which is authoritative and does not need this store — the mode exists for a future cached revocation list, and defaults to the safe answer. |
 */
@Component
class SharedStatePolicy(
    @Value("\${app.redis.failure-mode.rate-limit:ALLOW}") private val rateLimit: String,
    @Value("\${app.redis.failure-mode.presence:ALLOW}") private val presence: String,
    @Value("\${app.redis.failure-mode.revocation:DENY}") private val revocation: String,
) {

    fun rateLimitFailureMode(): FailureMode = parse(rateLimit, FailureMode.ALLOW)

    fun presenceFailureMode(): FailureMode = parse(presence, FailureMode.ALLOW)

    fun revocationFailureMode(): FailureMode = parse(revocation, FailureMode.DENY)

    private fun parse(raw: String, fallback: FailureMode): FailureMode =
        runCatching { FailureMode.valueOf(raw.trim().uppercase()) }.getOrDefault(fallback)
}
