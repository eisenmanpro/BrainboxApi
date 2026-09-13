package com.afrithecus.brainbox.api.identity

import com.afrithecus.brainbox.api.identity.repository.RefreshTokenRepository
import com.afrithecus.brainbox.api.identity.repository.UserSessionRepository
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration

/**
 * Keeps the authentication tables bounded. Expired refresh tokens can never be
 * exchanged, and a session that has been inactive for a month is only noise for
 * the dashboard activity pulse (which counts active sessions).
 */
@Component
class IdentityMaintenanceScheduler(
    private val refreshTokens: RefreshTokenRepository,
    private val sessions: UserSessionRepository,
    private val clock: Clock,
) {

    @Scheduled(initialDelay = 600_000, fixedDelay = 86_400_000)
    @Transactional
    fun prune() {
        refreshTokens.deleteByExpiresAtBefore(clock.instant())
        sessions.deleteByIsActiveFalseAndLastActiveAtBefore(
            clock.instant().minus(Duration.ofDays(INACTIVE_SESSION_RETENTION_DAYS))
        )
    }

    companion object {
        /** How long a logged-out session is retained before pruning. */
        const val INACTIVE_SESSION_RETENTION_DAYS = 30L
    }
}
