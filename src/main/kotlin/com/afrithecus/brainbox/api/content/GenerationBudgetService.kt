package com.afrithecus.brainbox.api.content

import com.afrithecus.brainbox.api.common.error.ApiErrorCode
import com.afrithecus.brainbox.api.common.error.ApiException
import com.afrithecus.brainbox.api.common.error.invalidArgument
import com.afrithecus.brainbox.api.content.repository.GenerationJobRepository
import com.afrithecus.brainbox.api.content.repository.ModelCallRepository
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * H3 daily generation budgets: the spend cap the enqueue path enforces before it
 * writes a row. Brainbox pays per token, so a runaway batch or a retry storm must
 * be stopped at admission rather than after the provider is called.
 *
 * Two runtime policy keys (same `moderation_policies` table as moderation) cap the
 * number of jobs newly enqueued in the current UTC day:
 *
 * - `generation_daily_job_budget` (platform-wide, default 500).
 * - `generation_daily_school_job_budget` (per school, default 100).
 *
 * A value of 0 - or a directly stored negative - means unlimited, so a bad console
 * write can never block all generation. The platform-wide cap always applies; a job
 * with no school (platform batch/proactive work) counts only against it. The window
 * is the calendar UTC day, which is cheap to count with the `(school_id, created_at)`
 * index and needs no Redis.
 *
 * Idempotent re-enqueue is intentionally not charged: a job key that already exists
 * creates no new work, so [GenerationJobService.enqueue] checks for the row before
 * calling [enforce].
 */
@Service
class GenerationBudgetService(
    private val generationJobs: GenerationJobRepository,
    private val modelCalls: ModelCallRepository,
    private val moderationPolicy: ModerationPolicyService,
    private val clock: Clock,
) {

    /** Effective platform-wide daily budget; <= 0 means unlimited. */
    fun platformBudget(): Int = moderationPolicy.generationDailyJobBudget()

    /** Effective per-school daily budget; <= 0 means unlimited. */
    fun schoolBudget(): Int = moderationPolicy.generationDailySchoolJobBudget()

    /** Jobs created platform-wide since the start of the current UTC day. */
    fun platformUsedToday(): Long = generationJobs.countByCreatedAtGreaterThanEqual(startOfUtcDay())

    /** Jobs created for [schoolId] since the start of the current UTC day. */
    fun schoolUsedToday(schoolId: UUID): Long =
        generationJobs.countBySchoolIdAndCreatedAtGreaterThanEqual(schoolId, startOfUtcDay())

    /** Effective platform-wide daily spend budget in micros; <= 0 means unlimited. */
    fun platformCostBudget(): Long = moderationPolicy.generationDailyCostBudgetMicros()

    /** Effective per-school daily spend budget in micros; <= 0 means unlimited. */
    fun schoolCostBudget(): Long = moderationPolicy.generationDailySchoolCostBudgetMicros()

    /** Provider cost in micros recorded platform-wide since the start of the UTC day. */
    fun platformCostUsedToday(): Long = modelCalls.sumCostSince(startOfUtcDay()) ?: 0L

    /** Provider cost in micros recorded for [schoolId] since the start of the UTC day. */
    fun schoolCostUsedToday(schoolId: UUID): Long =
        modelCalls.sumCostForSchoolSince(schoolId, startOfUtcDay()) ?: 0L

    /**
     * Throws a 429 [ApiException] when the daily budget is already exhausted. Called
     * before any row is written, so an over-budget enqueue never partially writes.
     * A null [schoolId] (platform batch/proactive work) is checked against the
     * platform budgets only.
     *
     * Both dimensions are checked: the job-count budget caps volume, and the spend
     * budget caps the money already accrued today (from stored model_calls costs).
     * The spend check is admission-time and uses observed spend, so it stops the
     * next job once the day is already over budget rather than predicting a price.
     */
    fun enforce(schoolId: UUID?) {
        if (schoolId != null) {
            val limit = schoolBudget()
            if (limit > 0) {
                val used = schoolUsedToday(schoolId)
                if (used >= limit) throw blocked("school", schoolId, limit, used)
            }
            val costLimit = schoolCostBudget()
            if (costLimit > 0) {
                val used = schoolCostUsedToday(schoolId)
                if (used >= costLimit) throw blockedCost("school", schoolId, costLimit, used)
            }
        }
        val platformLimit = platformBudget()
        if (platformLimit > 0) {
            val used = platformUsedToday()
            if (used >= platformLimit) throw blocked("platform", null, platformLimit, used)
        }
        val platformCostLimit = platformCostBudget()
        if (platformCostLimit > 0) {
            val used = platformCostUsedToday()
            if (used >= platformCostLimit) throw blockedCost("platform", null, platformCostLimit, used)
        }
    }

    /**
     * Console write for one or both budgets. At least one value is required; a
     * negative value is rejected (0 is allowed and means unlimited).
     */
    fun setBudgets(
        platformDailyJobs: Int?,
        schoolDailyJobs: Int?,
        platformDailyCostMicros: Long? = null,
        schoolDailyCostMicros: Long? = null,
    ) {
        if (platformDailyJobs == null && schoolDailyJobs == null &&
            platformDailyCostMicros == null && schoolDailyCostMicros == null
        ) {
            throw invalidArgument("at least one daily budget value is required")
        }
        platformDailyJobs?.let { moderationPolicy.setGenerationDailyJobBudget(it) }
        schoolDailyJobs?.let { moderationPolicy.setGenerationDailySchoolJobBudget(it) }
        platformDailyCostMicros?.let { moderationPolicy.setGenerationDailyCostBudgetMicros(it) }
        schoolDailyCostMicros?.let { moderationPolicy.setGenerationDailySchoolCostBudgetMicros(it) }
    }

    /**
     * Number of schools already at or over their daily budget today, for the admin
     * read. Null when the per-school budget is unlimited (the count is meaningless).
     */
    fun schoolsOverBudget(): Long? {
        val limit = schoolBudget()
        if (limit <= 0) return null
        return generationJobs.countGroupedBySchoolSince(startOfUtcDay()).count { it.total >= limit }.toLong()
    }

    private fun blocked(scope: String, schoolId: UUID?, limit: Int, used: Long): ApiException {
        val window = "UTC day " + startOfUtcDay().atZone(ZoneOffset.UTC).toLocalDate()
        val subject = if (schoolId == null) "platform" else "school " + schoolId
        return ApiException(
            ApiErrorCode.TOO_MANY_REQUESTS,
            "daily generation budget reached: " + subject + " budget of " + limit +
                " jobs for " + window + " is exhausted (used " + used + ")",
            mapOf(
                "budget" to scope,
                "limit" to limit,
                "used" to used,
                "window" to window,
                "retryAfter" to secondsUntilNextUtcDay(),
            ),
        )
    }

    private fun blockedCost(scope: String, schoolId: UUID?, limitMicros: Long, usedMicros: Long): ApiException {
        val window = "UTC day " + startOfUtcDay().atZone(ZoneOffset.UTC).toLocalDate()
        val subject = if (schoolId == null) "platform" else "school " + schoolId
        return ApiException(
            ApiErrorCode.TOO_MANY_REQUESTS,
            "daily generation cost budget reached: " + subject + " budget of " + limitMicros +
                " micros for " + window + " is exhausted (used " + usedMicros + ")",
            mapOf(
                "budget" to "cost",
                "scope" to scope,
                "limitMicros" to limitMicros,
                "usedMicros" to usedMicros,
                "window" to window,
                "retryAfter" to secondsUntilNextUtcDay(),
            ),
        )
    }

    private fun startOfUtcDay(): Instant =
        clock.instant().atZone(ZoneOffset.UTC).toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant()

    private fun secondsUntilNextUtcDay(): Long {
        val next = startOfUtcDay().plus(Duration.ofDays(1))
        return Duration.between(clock.instant(), next).seconds.coerceAtLeast(1)
    }
}
