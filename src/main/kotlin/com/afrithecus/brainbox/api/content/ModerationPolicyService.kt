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

    private fun raw(key: String): String? =
        policies.findByPolicyKey(key)?.valueJson?.trim()?.takeIf { it.isNotEmpty() }

    private companion object {
        const val KEY_QUORUM_REQUIRED = "quorum_required"
        const val KEY_AUTO_APPROVE_ENABLED = "auto_approve_enabled"
        const val KEY_AUTO_APPROVE_MIN_VALIDATOR_SCORE = "auto_approve_min_validator_score"
        const val KEY_AUTO_APPROVE_MIN_QUESTIONS = "auto_approve_min_questions"
        const val KEY_AUTO_APPROVE_MIN_CRITIC_CONFIDENCE = "auto_approve_min_critic_confidence"
        const val KEY_ANSWER_KEY_MIN_AGREEMENT = "answer_key_min_agreement"
        const val DEFAULT_QUORUM_REQUIRED = 2
        const val DEFAULT_AUTO_APPROVE_ENABLED = true
        const val DEFAULT_AUTO_APPROVE_MIN_VALIDATOR_SCORE = 1.0
        const val DEFAULT_AUTO_APPROVE_MIN_QUESTIONS = 8
        const val DEFAULT_AUTO_APPROVE_MIN_CRITIC_CONFIDENCE = 0.90
        const val DEFAULT_ANSWER_KEY_MIN_AGREEMENT = 1.0
    }
}
