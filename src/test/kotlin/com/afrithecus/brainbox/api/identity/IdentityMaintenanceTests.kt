package com.afrithecus.brainbox.api.identity

import com.afrithecus.brainbox.api.identity.entity.RefreshTokenEntity
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.entity.UserSessionEntity
import com.afrithecus.brainbox.api.identity.model.Role
import com.afrithecus.brainbox.api.identity.model.SessionRole
import com.afrithecus.brainbox.api.identity.repository.RefreshTokenRepository
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import com.afrithecus.brainbox.api.identity.repository.UserSessionRepository
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant
import java.util.UUID

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class IdentityMaintenanceTests(
    @Autowired private val scheduler: IdentityMaintenanceScheduler,
    @Autowired private val refreshTokens: RefreshTokenRepository,
    @Autowired private val sessions: UserSessionRepository,
    @Autowired private val userRepository: UserRepository,
) {

    @Test
    fun `prune removes expired refresh tokens and long-dead sessions`() {
        val user = UserEntity()
        user.phoneNumber = "0700000910"
        user.email = "maintenance@auth.test"
        user.passwordHash = "x"
        user.name = "Maintenance User"
        user.role = Role.STUDENT
        user.isVerified = true
        user.isActive = true
        userRepository.save(user)

        val expired = refreshTokens.save(RefreshTokenEntity().apply {
            userId = user.id
            tokenHash = "a".repeat(64)
            family = UUID.randomUUID()
            expiresAt = Instant.now().minus(Duration.ofDays(1))
        })
        val live = refreshTokens.save(RefreshTokenEntity().apply {
            userId = user.id
            tokenHash = "b".repeat(64)
            family = UUID.randomUUID()
            expiresAt = Instant.now().plus(Duration.ofDays(1))
        })
        val dead = sessions.save(UserSessionEntity().apply {
            userId = user.id
            deviceId = "device-dead"
            role = SessionRole.STUDENT
            isActive = false
            lastActiveAt = Instant.now().minus(Duration.ofDays(40))
        })
        val active = sessions.save(UserSessionEntity().apply {
            userId = user.id
            deviceId = "device-active"
            role = SessionRole.STUDENT
            isActive = true
            lastActiveAt = Instant.now().minus(Duration.ofDays(40))
        })

        scheduler.prune()

        check(refreshTokens.findById(expired.id).isEmpty)
        check(refreshTokens.findById(live.id).isPresent)
        check(sessions.findById(dead.id).isEmpty)
        check(sessions.findById(active.id).isPresent)
    }
}
