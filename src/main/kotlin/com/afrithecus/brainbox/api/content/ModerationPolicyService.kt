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
 * `true`); keys currently defined are quorum_required, weighted_mode and
 * auto_approve_enabled.
 */
@Service
class ModerationPolicyService(
    private val policies: ModerationPolicyRepository,
    private val mapper: ObjectMapper,
) {

    /** Distinct-human approvals required to resolve a content version. */
    fun quorumRequired(): Int = readInt(KEY_QUORUM_REQUIRED, DEFAULT_QUORUM_REQUIRED)

    /** Whether expert weight may count toward the quorum (distinct humans still required). */
    fun weightedMode(): Boolean = readBoolean(KEY_WEIGHTED_MODE, DEFAULT_WEIGHTED_MODE)

    /** Confidence auto-approval, off by default; the console flips it per domain. */
    fun autoApproveEnabled(): Boolean = readBoolean(KEY_AUTO_APPROVE_ENABLED, DEFAULT_AUTO_APPROVE_ENABLED)

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

    private fun raw(key: String): String? =
        policies.findByPolicyKey(key)?.valueJson?.trim()?.takeIf { it.isNotEmpty() }

    private companion object {
        const val KEY_QUORUM_REQUIRED = "quorum_required"
        const val KEY_WEIGHTED_MODE = "weighted_mode"
        const val KEY_AUTO_APPROVE_ENABLED = "auto_approve_enabled"
        const val DEFAULT_QUORUM_REQUIRED = 2
        const val DEFAULT_WEIGHTED_MODE = false
        const val DEFAULT_AUTO_APPROVE_ENABLED = false
    }
}
