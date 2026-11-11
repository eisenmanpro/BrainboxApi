package com.afrithecus.brainbox.api.achievements

import com.afrithecus.brainbox.api.identity.model.Role
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Server-owned badge sweep (F5-A1). The client no longer awards badges; this
 * periodic pass is the safety net for activity that did not flow through the XP
 * endpoint (exam/contest/project completions). Each user is evaluated in its own
 * transaction by [AchievementsService.awardEligibleBadges].
 */
@Component
class BadgeAwardScheduler(
    private val achievementsService: AchievementsService,
    private val userRepository: UserRepository,
) {

    @Scheduled(
        initialDelayString = "\${app.achievements.badge-scan-initial-delay-ms:180000}",
        fixedDelayString = "\${app.achievements.badge-scan-interval-ms:3600000}",
    )
    fun sweep() {
        val users = runCatching { userRepository.findAllByRoleAndIsActiveTrue(Role.STUDENT) }.getOrElse {
            log.warn("Badge sweep could not load learners", it)
            return
        }
        var awarded = 0
        for (user in users) {
            awarded += runCatching { achievementsService.awardEligibleBadges(user.id).size }.getOrElse {
                log.warn("Badge sweep failed for user {}", user.id, it)
                0
            }
        }
        if (awarded > 0) log.info("Badge sweep awarded {} badge(s)", awarded)
    }

    private companion object {
        val log = LoggerFactory.getLogger(BadgeAwardScheduler::class.java)
    }
}
