package com.afrithecus.brainbox.api.content

import com.afrithecus.brainbox.api.common.error.invalidArgument
import com.afrithecus.brainbox.api.content.entity.ModerationPolicyEntity
import com.afrithecus.brainbox.api.content.repository.ModerationPolicyRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper

/**
 * Phase 7.4a moderation policy access. The defaults live in code and the
 * moderation_policies table is an override: a blank/absent/malformed row always
 * falls back to the code default, so a bad console write can never break review.
 *
 * The value_json column holds one JSON value per policy key (e.g. `2` or
 * `true`). Keys currently defined:
 *
 * - `quorum_required` (int, default 2): distinct-human approvals for the human path.
 * - `auto_approve_enabled` (boolean, default true): machine-first bulk approval.
 * - `auto_approve_min_validator_score` (double, default 1.0): zero validator findings.
 * - `auto_approve_min_questions` (int, default 8): minimum questions for an assessment task type
 *   (QUIZ/EXAM/ASSESSMENT); a NOTES unit with nested checks is not held to the floor.
 * - `auto_approve_min_critic_confidence` (double, default 0.90): model-confidence floor,
 *   fail-closed when the unit carries no confidence.
 * - `answer_key_min_agreement` (double, default 1.0): minimum independent answer-key
 *   agreement for an assessment; 1.0 means every stored key must agree with the
 *   independent solve, and an unverified unit fails closed.
 * - `answer_key_drop_disagreements` (boolean, default true): per-question disposition of
 *   disputed keys. When true, a question whose independent solve disagrees (including a
 *   dropped/blank answer or a missing stored key) is deleted from the unit and the
 *   surviving keys are compared at 1.0; when false, the old whole-unit ratio behaviour
 *   applies and nothing is deleted.
 * - `auto_approve_audit_sample_percent` (double, default 2.0): the deterministic
 *   percentage of machine approvals flagged for human spot-checking (O1). A stable
 *   hash of the content id decides the flag, so the sample is reproducible; 0
 *   disables sampling.
 *
 * H2 operational keys (same table, same class - deliberately not renamed). They exist so
 * an operator can pause or narrow autonomous generation at runtime instead of
 * redeploying; both fall back to the `app.content.worker` defaults when absent or
 * malformed:
 *
 * - `content_worker_paused` (boolean, default false): when true the worker claims
 *   nothing, so user-triggered and autonomous generation both stop.
 * - `content_worker_sources` (JSON array of strings, default every source): the job
 *   sources the worker may claim, for example `["USER"]` to keep user requests draining
 *   while batch/proactive material generation pauses. An empty array claims nothing.
 *
 * H3 daily generation budget keys (same table, same class - still not renamed):
 *
 * - `generation_daily_job_budget` (int, default 500): maximum generation jobs that may be
 *   newly enqueued platform-wide per UTC day. 0 or a negative value means unlimited.
 * - `generation_daily_school_job_budget` (int, default 100): maximum generation jobs that
 *   may be newly enqueued for one school per UTC day. 0 or a negative value means unlimited.
 *   The platform-wide cap always applies too; a job with no school (platform batch/proactive
 *   work) counts only against the platform cap.
 *
 * H3 spend budget keys (cost, not job count), also opt-in:
 *
 * - generation_daily_cost_budget_micros (long, default 0 = unlimited): maximum provider
 *   cost in micros that may be accrued platform-wide in the current UTC day.
 * - generation_daily_school_cost_budget_micros (long, default 0 = unlimited): the same
 *   cap for one school; platform-scope work is charged to the platform cap only.
 */
@Service
class ModerationPolicyService(
    private val policies: ModerationPolicyRepository,
    private val mapper: ObjectMapper,
) {

    /** Distinct-human approvals required to resolve a content version. */
    fun quorumRequired(): Int = readInt(KEY_QUORUM_REQUIRED, DEFAULT_QUORUM_REQUIRED)

    /** Machine-first bulk approval; on by default so clean units publish without a human. */
    fun autoApproveEnabled(): Boolean = readBoolean(KEY_AUTO_APPROVE_ENABLED, DEFAULT_AUTO_APPROVE_ENABLED)

    /** Minimum validator score for an auto-approval; 1.0 means zero findings. */
    fun autoApproveMinValidatorScore(): Double =
        readDouble(KEY_AUTO_APPROVE_MIN_VALIDATOR_SCORE, DEFAULT_AUTO_APPROVE_MIN_VALIDATOR_SCORE)

    /** Minimum question count when a unit has questions. */
    fun autoApproveMinQuestions(): Int =
        readInt(KEY_AUTO_APPROVE_MIN_QUESTIONS, DEFAULT_AUTO_APPROVE_MIN_QUESTIONS)

    /** Minimum critic confidence when a unit has questions; a null confidence fails closed. */
    fun autoApproveMinCriticConfidence(): Double =
        readDouble(KEY_AUTO_APPROVE_MIN_CRITIC_CONFIDENCE, DEFAULT_AUTO_APPROVE_MIN_CRITIC_CONFIDENCE)

    /**
     * Phase 7.5f: minimum independent answer-key agreement for an assessment. The
     * default 1.0 requires every key to agree; a lower value is a deliberate,
     * console-set relaxation (for example a small tolerance for free-text answers),
     * never an implicit one.
     */
    fun answerKeyMinAgreement(): Double =
        readDouble(KEY_ANSWER_KEY_MIN_AGREEMENT, DEFAULT_ANSWER_KEY_MIN_AGREEMENT)

    /**
     * Phase 7.5h: when true (default), a disputed question is dropped from its unit so an
     * otherwise accurate quiz is not discarded over one or two items; only the surviving
     * keys must agree. When false, the whole-unit agreement ratio is kept and nothing is
     * deleted, so any disagreement leaves the unit below the (default 1.0) bar.
     */
    fun answerKeyDropDisagreements(): Boolean =
        readBoolean(KEY_ANSWER_KEY_DROP_DISAGREEMENTS, DEFAULT_ANSWER_KEY_DROP_DISAGREEMENTS)

    /**
     * O1: the deterministic percentage of machine approvals to flag for human
     * spot-checking. Defaults to [AuditSampling.DEFAULT_PERCENT]; 0 disables the
     * sample. Values outside 0..100 are clamped by [AuditSampling.isSampled].
     */
    fun autoApproveAuditSamplePercent(): Double =
        readDouble(KEY_AUTO_APPROVE_AUDIT_SAMPLE_PERCENT, AuditSampling.DEFAULT_PERCENT)

    /** Console write: sets the sampled-audit percentage; a negative value is rejected. */
    @Transactional
    fun setAutoApproveAuditSamplePercent(percent: Double) {
        if (percent < 0.0) throw invalidArgument("autoApproveAuditSamplePercent must be zero or positive")
        set(KEY_AUTO_APPROVE_AUDIT_SAMPLE_PERCENT, mapper.writeValueAsString(percent))
    }

    /**
     * H2 operational policy: when true the generation worker claims nothing, which
     * pauses autonomous (BATCH/PROACTIVE) and user-triggered work without a
     * redeploy. This is one of the operational keys stored in the same
     * `moderation_policies` table as the moderation keys; the class is not renamed.
     * An absent/malformed row falls back to [fallback] (the
     * `app.content.worker.paused` default).
     */
    fun contentWorkerPaused(fallback: Boolean = false): Boolean =
        readBoolean(KEY_CONTENT_WORKER_PAUSED, fallback)

    /**
     * H2 operational policy: the job sources the worker may claim, as a JSON array
     * of strings (for example `["USER","BATCH"]`). An absent, non-array or
     * all-unknown value falls back to [fallback] (the
     * `app.content.worker.sources` default); an explicit empty array means
     * "claim no source". Unknown names are dropped so a stale console write can
     * never enable an undefined source.
     */
    fun contentWorkerSources(fallback: List<String>): List<String> {
        val json = raw(KEY_CONTENT_WORKER_SOURCES) ?: return fallback
        return runCatching {
            val node = mapper.readTree(json)
            if (!node.isArray) return fallback
            if (node.size() == 0) return emptyList()
            val names = (0 until node.size())
                .mapNotNull { index -> runCatching { node.get(index).asString() }.getOrNull() }
                .map { it.trim().uppercase() }
                .filter { it.isNotEmpty() }
            val known = names.filter { GenerationJobSource.isKnown(it) }.distinct()
            if (known.isEmpty()) fallback else known
        }.getOrElse { fallback }
    }

    /** Console write: pauses or resumes the generation worker. */
    @Transactional
    fun setContentWorkerPaused(paused: Boolean) {
        set(KEY_CONTENT_WORKER_PAUSED, mapper.writeValueAsString(paused))
    }

    /** Console write: sets the sources the generation worker may claim. */
    @Transactional
    fun setContentWorkerSources(sources: List<String>) {
        set(KEY_CONTENT_WORKER_SOURCES, mapper.writeValueAsString(sources))
    }

    /**
     * H3 operational policy: the maximum number of generation jobs that may be
     * newly enqueued platform-wide in the current UTC day. Defaults to
     * [DEFAULT_GENERATION_DAILY_JOB_BUDGET]; 0 or a negative value means unlimited,
     * so a direct/legacy write can never block all generation.
     */
    fun generationDailyJobBudget(): Int =
        readInt(KEY_GENERATION_DAILY_JOB_BUDGET, DEFAULT_GENERATION_DAILY_JOB_BUDGET)

    /**
     * H3 operational policy: the maximum number of generation jobs that may be
     * newly enqueued for one school in the current UTC day. Defaults to
     * [DEFAULT_GENERATION_DAILY_SCHOOL_JOB_BUDGET]; 0 or a negative value means
     * unlimited. The platform-wide budget still applies.
     */
    fun generationDailySchoolJobBudget(): Int =
        readInt(KEY_GENERATION_DAILY_SCHOOL_JOB_BUDGET, DEFAULT_GENERATION_DAILY_SCHOOL_JOB_BUDGET)

    /** Console write: sets the platform-wide daily generation budget; a negative value is rejected. */
    @Transactional
    fun setGenerationDailyJobBudget(budget: Int) {
        if (budget < 0) throw invalidArgument("generationDailyJobBudget must be zero or positive")
        set(KEY_GENERATION_DAILY_JOB_BUDGET, mapper.writeValueAsString(budget))
    }

    /** Console write: sets the per-school daily generation budget; a negative value is rejected. */
    @Transactional
    fun setGenerationDailySchoolJobBudget(budget: Int) {
        if (budget < 0) throw invalidArgument("generationDailySchoolJobBudget must be zero or positive")
        set(KEY_GENERATION_DAILY_SCHOOL_JOB_BUDGET, mapper.writeValueAsString(budget))
    }

    /**
     * H3 spend budget: the maximum provider cost in micros that may be accrued
     * platform-wide in the current UTC day. Defaults to
     * [DEFAULT_GENERATION_DAILY_COST_BUDGET_MICROS]; 0 or a negative value means
     * unlimited, so a direct write can never block all generation.
     */
    fun generationDailyCostBudgetMicros(): Long =
        readLong(KEY_GENERATION_DAILY_COST_BUDGET_MICROS, DEFAULT_GENERATION_DAILY_COST_BUDGET_MICROS)

    /**
     * H3 spend budget for one school in the current UTC day. Defaults to
     * [DEFAULT_GENERATION_DAILY_SCHOOL_COST_BUDGET_MICROS]; 0 or a negative value
     * means unlimited. The platform cap still applies.
     */
    fun generationDailySchoolCostBudgetMicros(): Long =
        readLong(KEY_GENERATION_DAILY_SCHOOL_COST_BUDGET_MICROS, DEFAULT_GENERATION_DAILY_SCHOOL_COST_BUDGET_MICROS)

    /** Console write: sets the platform-wide daily spend budget; a negative value is rejected. */
    @Transactional
    fun setGenerationDailyCostBudgetMicros(budget: Long) {
        if (budget < 0) throw invalidArgument("generationDailyCostBudgetMicros must be zero or positive")
        set(KEY_GENERATION_DAILY_COST_BUDGET_MICROS, mapper.writeValueAsString(budget))
    }

    /** Console write: sets the per-school daily spend budget; a negative value is rejected. */
    @Transactional
    fun setGenerationDailySchoolCostBudgetMicros(budget: Long) {
        if (budget < 0) throw invalidArgument("generationDailySchoolCostBudgetMicros must be zero or positive")
        set(KEY_GENERATION_DAILY_SCHOOL_COST_BUDGET_MICROS, mapper.writeValueAsString(budget))
    }

    // ------------------------------------------------------------------ media security
    //
    // The upload security posture is a runtime policy, not a redeploy: a deployment that
    // has ClamAV turns it on (and off) here, and the console can relax or tighten URL
    // reputation and infection handling without a release. Every getter falls back to the
    // `app.media.scan` / `app.media.url-reputation` deployment default when the row is
    // absent or malformed, so a bad console write cannot leave uploads unguarded.

    /** Whether uploads are scanned at all. Absent → the deployment default. */
    fun mediaScanEnabled(fallback: Boolean): Boolean = readBoolean(KEY_MEDIA_SCAN_ENABLED, fallback)

    /** The scanner: none, clamav or http. Absent/unknown → the deployment default. */
    fun mediaScanProvider(fallback: String): String {
        val stored = raw(KEY_MEDIA_SCAN_PROVIDER)?.let { runCatching { mapper.readValue(it, String::class.java) }.getOrNull() }
        val normalized = stored?.trim()?.lowercase()
        return if (normalized in KNOWN_SCAN_PROVIDERS) normalized!! else fallback.trim().lowercase()
    }

    /** Accept an object a scanner could not judge. Absent → the deployment default. */
    fun mediaScanFailOpen(fallback: Boolean): Boolean = readBoolean(KEY_MEDIA_SCAN_FAIL_OPEN, fallback)

    /** What to do with a refused object: QUARANTINE or DELETE. */
    fun mediaOnInfection(fallback: String): String {
        val stored = raw(KEY_MEDIA_ON_INFECTION)?.let { runCatching { mapper.readValue(it, String::class.java) }.getOrNull() }
        val normalized = stored?.trim()?.uppercase()
        return if (normalized in KNOWN_INFECTION_ACTIONS) normalized!! else fallback.trim().uppercase()
    }

    /** Whether a refusal or a scanner failure raises a console alert. */
    fun mediaAlertEnabled(fallback: Boolean): Boolean = readBoolean(KEY_MEDIA_ALERT_ENABLED, fallback)

    /** Whether URLs are reputation-checked before they are stored or fetched. */
    fun mediaUrlReputationEnabled(fallback: Boolean): Boolean =
        readBoolean(KEY_MEDIA_URL_REPUTATION_ENABLED, fallback)

    /** The URL check provider: none, deny_list or http. */
    fun mediaUrlReputationProvider(fallback: String): String {
        val stored = raw(KEY_MEDIA_URL_REPUTATION_PROVIDER)
            ?.let { runCatching { mapper.readValue(it, String::class.java) }.getOrNull() }
        val normalized = stored?.trim()?.lowercase()
        return if (normalized in KNOWN_URL_PROVIDERS) normalized!! else fallback.trim().lowercase()
    }

    /** Whether an unreachable URL-reputation provider blocks the URL. */
    fun mediaUrlReputationFailOpen(fallback: Boolean): Boolean =
        readBoolean(KEY_MEDIA_URL_REPUTATION_FAIL_OPEN, fallback)

    /** Console write: turns scanning on or off. */
    @Transactional
    fun setMediaScanEnabled(enabled: Boolean) = set(KEY_MEDIA_SCAN_ENABLED, mapper.writeValueAsString(enabled))

    /** Console write: switches the scanner; an unknown provider is rejected. */
    @Transactional
    fun setMediaScanProvider(provider: String) {
        val normalized = provider.trim().lowercase()
        if (normalized !in KNOWN_SCAN_PROVIDERS) {
            throw invalidArgument("mediaScanProvider must be one of " + KNOWN_SCAN_PROVIDERS.joinToString(", "))
        }
        set(KEY_MEDIA_SCAN_PROVIDER, mapper.writeValueAsString(normalized))
    }

    /** Console write: accept or refuse an object the scanner could not judge. */
    @Transactional
    fun setMediaScanFailOpen(failOpen: Boolean) = set(KEY_MEDIA_SCAN_FAIL_OPEN, mapper.writeValueAsString(failOpen))

    /** Console write: quarantine (default) or delete a refused object. */
    @Transactional
    fun setMediaOnInfection(action: String) {
        val normalized = action.trim().uppercase()
        if (normalized !in KNOWN_INFECTION_ACTIONS) {
            throw invalidArgument("mediaOnInfection must be QUARANTINE or DELETE")
        }
        set(KEY_MEDIA_ON_INFECTION, mapper.writeValueAsString(normalized))
    }

    /** Console write: raise (or silence) media security alerts. */
    @Transactional
    fun setMediaAlertEnabled(enabled: Boolean) = set(KEY_MEDIA_ALERT_ENABLED, mapper.writeValueAsString(enabled))

    /** Console write: turn URL reputation on or off. */
    @Transactional
    fun setMediaUrlReputationEnabled(enabled: Boolean) =
        set(KEY_MEDIA_URL_REPUTATION_ENABLED, mapper.writeValueAsString(enabled))

    /** Console write: choose the URL reputation provider. */
    @Transactional
    fun setMediaUrlReputationProvider(provider: String) {
        val normalized = provider.trim().lowercase()
        if (normalized !in KNOWN_URL_PROVIDERS) {
            throw invalidArgument("mediaUrlReputationProvider must be one of " + KNOWN_URL_PROVIDERS.joinToString(", "))
        }
        set(KEY_MEDIA_URL_REPUTATION_PROVIDER, mapper.writeValueAsString(normalized))
    }

    /** Console write: block or allow a URL when the reputation provider cannot answer. */
    @Transactional
    fun setMediaUrlReputationFailOpen(failOpen: Boolean) =
        set(KEY_MEDIA_URL_REPUTATION_FAIL_OPEN, mapper.writeValueAsString(failOpen))

    /** Console write: sets one policy override to a JSON value. */
    @Transactional
    fun set(key: String, json: String) {
        val normalizedKey = key.trim()
        if (normalizedKey.isEmpty()) throw invalidArgument("policy key must not be blank")
        val value = json.trim()
        if (value.isEmpty()) throw invalidArgument("policy value must be non-empty JSON")
        runCatching { mapper.readTree(value) }.getOrElse { failure ->
            throw invalidArgument("policy value is not valid JSON: " + (failure.message ?: "parse error"))
        }

        val entity = policies.findByPolicyKey(normalizedKey)
            ?: ModerationPolicyEntity().apply { policyKey = normalizedKey }
        entity.valueJson = value
        policies.save(entity)
    }

    private fun readInt(key: String, fallback: Int): Int =
        raw(key)?.let { runCatching { mapper.readValue(it, Int::class.javaObjectType) }.getOrNull() } ?: fallback

    private fun readBoolean(key: String, fallback: Boolean): Boolean =
        raw(key)?.let { runCatching { mapper.readValue(it, Boolean::class.javaObjectType) }.getOrNull() } ?: fallback

    private fun readDouble(key: String, fallback: Double): Double =
        raw(key)?.let { runCatching { mapper.readValue(it, Double::class.javaObjectType) }.getOrNull() } ?: fallback

    private fun readLong(key: String, fallback: Long): Long =
        raw(key)?.let { runCatching { mapper.readValue(it, Long::class.javaObjectType) }.getOrNull() } ?: fallback

    private fun raw(key: String): String? =
        policies.findByPolicyKey(key)?.valueJson?.trim()?.takeIf { it.isNotEmpty() }

    private companion object {
        const val KEY_QUORUM_REQUIRED = "quorum_required"
        const val KEY_AUTO_APPROVE_ENABLED = "auto_approve_enabled"
        const val KEY_AUTO_APPROVE_MIN_VALIDATOR_SCORE = "auto_approve_min_validator_score"
        const val KEY_AUTO_APPROVE_MIN_QUESTIONS = "auto_approve_min_questions"
        const val KEY_AUTO_APPROVE_MIN_CRITIC_CONFIDENCE = "auto_approve_min_critic_confidence"
        const val KEY_ANSWER_KEY_MIN_AGREEMENT = "answer_key_min_agreement"
        const val KEY_ANSWER_KEY_DROP_DISAGREEMENTS = "answer_key_drop_disagreements"
        const val KEY_AUTO_APPROVE_AUDIT_SAMPLE_PERCENT = "auto_approve_audit_sample_percent"

        /** H2 operational keys; defaults live in AppContentProperties.worker. */
        const val KEY_CONTENT_WORKER_PAUSED = "content_worker_paused"
        const val KEY_CONTENT_WORKER_SOURCES = "content_worker_sources"

        /** H3 daily generation budget keys; 0 (or a stored negative) means unlimited. */
        const val KEY_GENERATION_DAILY_JOB_BUDGET = "generation_daily_job_budget"
        const val KEY_GENERATION_DAILY_SCHOOL_JOB_BUDGET = "generation_daily_school_job_budget"
        const val KEY_GENERATION_DAILY_COST_BUDGET_MICROS = "generation_daily_cost_budget_micros"
        const val KEY_GENERATION_DAILY_SCHOOL_COST_BUDGET_MICROS = "generation_daily_school_cost_budget_micros"
        const val DEFAULT_GENERATION_DAILY_JOB_BUDGET = 500
        const val DEFAULT_GENERATION_DAILY_SCHOOL_JOB_BUDGET = 100
        const val DEFAULT_GENERATION_DAILY_COST_BUDGET_MICROS = 0L
        const val DEFAULT_GENERATION_DAILY_SCHOOL_COST_BUDGET_MICROS = 0L
        const val DEFAULT_QUORUM_REQUIRED = 2
        const val DEFAULT_AUTO_APPROVE_ENABLED = true
        const val DEFAULT_AUTO_APPROVE_MIN_VALIDATOR_SCORE = 1.0
        const val DEFAULT_AUTO_APPROVE_MIN_QUESTIONS = 8
        const val DEFAULT_AUTO_APPROVE_MIN_CRITIC_CONFIDENCE = 0.90
        const val DEFAULT_ANSWER_KEY_MIN_AGREEMENT = 1.0
        const val DEFAULT_ANSWER_KEY_DROP_DISAGREEMENTS = true

        /** Media security keys; the deployment defaults come from app.media.* config. */
        const val KEY_MEDIA_SCAN_ENABLED = "media_scan_enabled"
        const val KEY_MEDIA_SCAN_PROVIDER = "media_scan_provider"
        const val KEY_MEDIA_SCAN_FAIL_OPEN = "media_scan_fail_open"
        const val KEY_MEDIA_ON_INFECTION = "media_on_infection"
        const val KEY_MEDIA_ALERT_ENABLED = "media_alert_enabled"
        const val KEY_MEDIA_URL_REPUTATION_ENABLED = "media_url_reputation_enabled"
        const val KEY_MEDIA_URL_REPUTATION_PROVIDER = "media_url_reputation_provider"
        const val KEY_MEDIA_URL_REPUTATION_FAIL_OPEN = "media_url_reputation_fail_open"
        val KNOWN_SCAN_PROVIDERS = setOf("none", "clamav", "http")
        val KNOWN_URL_PROVIDERS = setOf("none", "deny_list", "http")
        val KNOWN_INFECTION_ACTIONS = setOf("QUARANTINE", "DELETE")
    }
}
