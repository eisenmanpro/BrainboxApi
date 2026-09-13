package com.afrithecus.brainbox.api.leaderboard

import com.afrithecus.brainbox.api.achievements.repository.UserAchievementsRepository
import com.afrithecus.brainbox.api.achievements.repository.UserBadgeRepository
import com.afrithecus.brainbox.api.achievements.repository.XpEventRepository
import com.afrithecus.brainbox.api.common.error.ApiErrorCode
import com.afrithecus.brainbox.api.common.error.ApiException
import com.afrithecus.brainbox.api.common.error.invalidArgument
import com.afrithecus.brainbox.api.common.error.notFound
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.model.CurrentUser
import com.afrithecus.brainbox.api.identity.model.Role
import com.afrithecus.brainbox.api.identity.model.SubRole
import com.afrithecus.brainbox.api.identity.repository.SchoolRepository
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import com.afrithecus.brainbox.api.leaderboard.entity.LeaderboardSeasonEntity
import com.afrithecus.brainbox.api.leaderboard.model.LeaderboardTimeframe
import com.afrithecus.brainbox.api.leaderboard.repository.LeaderboardSeasonRepository
import com.afrithecus.brainbox.api.leaderboard.web.AdminLeaderboardEntryPayload
import com.afrithecus.brainbox.api.mastery.repository.TopicMasteryRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import kotlin.math.roundToInt

/**
 * Admin leaderboard management (doc 08 §7). XP is event-sourced (V20 xp_events),
 * so every timeframe board is computed on the fly and a "reset" records a new
 * season boundary for that timeframe rather than deleting award history.
 *
 * A platform ADMIN sees every school or filters by [schoolId]; an ICT_ADMIN is
 * always confined to their own school. Resetting a season and recalculating the
 * XP totals are platform-admin operations.
 */
@Service
class LeaderboardService(
    private val xpEventRepository: XpEventRepository,
    private val userAchievementsRepository: UserAchievementsRepository,
    private val userBadgeRepository: UserBadgeRepository,
    private val topicMasteryRepository: TopicMasteryRepository,
    private val seasonRepository: LeaderboardSeasonRepository,
    private val userRepository: UserRepository,
    private val schoolRepository: SchoolRepository,
    private val clock: Clock,
    @Value("\${app.school-zone:Africa/Nairobi}") private val schoolZone: String,
) {

    @Transactional(readOnly = true)
    fun adminLeaderboard(
        current: CurrentUser,
        limit: Int,
        timeframeRaw: String?,
        gradeRaw: String?,
        subjectRaw: String?,
        schoolIdRaw: String?,
    ): List<AdminLeaderboardEntryPayload> {
        val actor = user(current)
        val schoolId = scopeSchool(actor, schoolIdRaw)
        val timeframe = parseTimeframe(timeframeRaw)
        val grade = gradeRaw?.trim()?.takeIf { it.isNotEmpty() }
        val subject = subjectRaw?.trim()?.takeIf { it.isNotEmpty() }
        val page = PageRequest.of(0, limit.coerceIn(1, MAX_LIMIT))

        val scores: List<Pair<UUID, Double>> = if (subject != null) {
            // A subject board ranks on mastery (0-100) for that subject, not on XP;
            // mastery is cumulative so the timeframe window does not apply.
            topicMasteryRepository.subjectLeaderboard(subject, schoolId, grade, page)
                .map { it.getUserId() to it.getScore() }
        } else {
            xpEventRepository.totalsSinceScoped(effectiveStart(timeframe), schoolId, grade, page)
                .map { it.getUserId() to it.getTotal().toDouble() }
        }
        if (scores.isEmpty()) return emptyList()

        val users = userRepository.findAllById(scores.map { it.first }).associateBy { it.id }
        val badgeCounts = userBadgeRepository.findAllByUserIdIn(scores.map { it.first })
            .groupingBy { it.userId }.eachCount()
        val schoolNames = schoolRepository.findAllById(users.values.mapNotNull { it.schoolId }.distinct())
            .associate { it.id to it.name }
        return scores.mapIndexed { index, (userId, score) ->
            val student = users[userId]
            AdminLeaderboardEntryPayload(
                rank = index + 1,
                userId = userId.toString(),
                studentName = student?.name ?: "Student",
                schoolName = student?.schoolId?.let { schoolNames[it] } ?: "",
                gradeLevel = student?.gradeLevel,
                score = round2(score),
                badgeCount = badgeCounts[userId] ?: 0,
            )
        }
    }

    /** Rebuilds every user's cached total XP from the xp_events ledger. */
    @Transactional
    fun recalculate(current: CurrentUser) {
        requirePlatformAdmin(user(current))
        recalculateTotalXp()
    }

    /** Starts a new season for [timeframeRaw]; resetting ALL also rebuilds total XP. */
    @Transactional
    fun reset(current: CurrentUser, timeframeRaw: String?): LeaderboardTimeframe {
        requirePlatformAdmin(user(current))
        val timeframe = parseTimeframe(timeframeRaw)
        val season = seasonRepository.findById(timeframe).orElse(null) ?: LeaderboardSeasonEntity(timeframe)
        season.startedAt = clock.instant()
        seasonRepository.save(season)
        if (timeframe == LeaderboardTimeframe.ALL) recalculateTotalXp()
        return timeframe
    }

    private fun recalculateTotalXp() {
        val since = seasonRepository.findById(LeaderboardTimeframe.ALL).orElse(null)?.startedAt ?: Instant.EPOCH
        val totals = xpEventRepository.totalsSince(since).associate { it.getUserId() to it.getTotal().toInt() }
        userAchievementsRepository.findAll().forEach { row ->
            val expected = totals[row.userId] ?: 0
            if (row.totalXp != expected) row.totalXp = expected
        }
    }

    /** A stored season start clamps a timeframe's window; otherwise the window is its own. */
    private fun effectiveStart(timeframe: LeaderboardTimeframe): Instant {
        val window = when (timeframe) {
            LeaderboardTimeframe.WEEKLY -> clock.instant().minus(Duration.ofDays(7))
            LeaderboardTimeframe.MONTHLY -> clock.instant().minus(Duration.ofDays(30))
            LeaderboardTimeframe.TERM -> currentTermStart()
            LeaderboardTimeframe.ALL -> Instant.EPOCH
        }
        val season = seasonRepository.findById(timeframe).orElse(null)?.startedAt ?: Instant.EPOCH
        return if (season.isAfter(window)) season else window
    }

    /** The client calendar (ExamTerm.current): Jan-Apr Term 1, May-Aug Term 2, else Term 3. */
    private fun currentTermStart(): Instant {
        val zone = runCatching { ZoneId.of(schoolZone) }.getOrDefault(ZoneOffset.UTC)
        val today = LocalDate.now(clock.withZone(zone))
        val startMonth = when (today.monthValue) {
            in 1..4 -> 1
            in 5..8 -> 5
            else -> 9
        }
        return LocalDate.of(today.year, startMonth, 1).atStartOfDay(zone).toInstant()
    }

    private fun scopeSchool(actor: UserEntity, schoolIdRaw: String?): UUID? {
        if (actor.role == Role.ADMIN) {
            val raw = schoolIdRaw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val id = runCatching { UUID.fromString(raw) }.getOrNull()
                ?: throw invalidArgument("schoolId is not a valid identifier")
            if (schoolRepository.findById(id).isEmpty) throw notFound("School not found")
            return id
        }
        if (actor.role == Role.TEACHER && actor.subRole == SubRole.ICT_ADMIN) {
            return actor.schoolId ?: throw ApiException(ApiErrorCode.FORBIDDEN, "ICT admin has no school")
        }
        throw ApiException(ApiErrorCode.FORBIDDEN, "Admin access required")
    }

    private fun requirePlatformAdmin(actor: UserEntity) {
        if (actor.role != Role.ADMIN) {
            throw ApiException(ApiErrorCode.FORBIDDEN, "Platform admin access required")
        }
    }

    private fun parseTimeframe(raw: String?): LeaderboardTimeframe =
        runCatching { LeaderboardTimeframe.valueOf(raw?.trim()?.uppercase() ?: "ALL") }
            .getOrElse { throw invalidArgument("timeframe must be one of WEEKLY, MONTHLY, TERM, ALL") }

    private fun user(current: CurrentUser): UserEntity =
        userRepository.findById(current.userId).orElse(null) ?: throw notFound("User not found")

    private fun round2(value: Double): Double = (value * 100).roundToInt() / 100.0

    companion object {
        const val MAX_LIMIT = 200
    }
}
