package com.afrithecus.brainbox.api.attendance

import com.afrithecus.brainbox.api.attendance.repository.AttendanceSmsDispatchRepository
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock

/**
 * P4.5: provider delivery-report (DLR) webhook for the attendance channel.
 *
 * The endpoint is authenticated by a shared secret (the provider cannot present a bearer token) and
 * is idempotent: a replayed report updates the same row without moving the first delivered_at. The
 * exact HostPinnacle DLR payload has not been observed yet, so message id and status are found by
 * normalised key names anywhere in the JSON object; an unrecognised payload is acknowledged with
 * 200 (so the provider stops retrying) but never guessed into a delivery.
 *
 * No phone number is logged. Rows without a provider message id (a failed send) simply cannot
 * receive a DLR, which is correct.
 */
@Service
class AttendanceSmsDlrService(
    private val properties: AttendanceSmsProperties,
    private val dispatches: AttendanceSmsDispatchRepository,
    private val mapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
    private val clock: Clock,
) {

    enum class Outcome {
        ACCEPTED,
        UNAUTHORIZED,
        DISABLED,
        BAD_PAYLOAD,
        UNKNOWN_MESSAGE,
    }

    enum class DlrStatus { DELIVERED, FAILED, UNKNOWN }

    @Transactional
    fun handle(secret: String?, body: String): Outcome {
        if (properties.dlrSecret.isBlank()) return Outcome.DISABLED
        if (!secretMatches(secret)) {
            meterRegistry.counter("brainbox.sms.dlr", "outcome", "unauthorized").increment()
            return Outcome.UNAUTHORIZED
        }
        val node = runCatching { mapper.readTree(body) }.getOrNull()
            ?: return outcome(Outcome.BAD_PAYLOAD)
        val messageId = findText(node, MESSAGE_ID_KEYS) ?: return outcome(Outcome.BAD_PAYLOAD)
        val status = classify(findText(node, STATUS_KEYS).orEmpty())
        val row = dispatches.findByProviderMessageId(messageId) ?: return outcome(Outcome.UNKNOWN_MESSAGE)
        if (row.dlrReceivedAt != null && row.dlrStatus == status.name) {
            meterRegistry.counter("brainbox.sms.dlr", "outcome", "replayed").increment()
            return Outcome.ACCEPTED
        }
        val now = clock.instant()
        row.dlrStatus = status.name
        row.dlrReceivedAt = now
        if (status == DlrStatus.DELIVERED && row.deliveredAt == null) row.deliveredAt = now
        dispatches.save(row)
        meterRegistry.counter("brainbox.sms.dlr", "outcome", status.name.lowercase()).increment()
        log.info("SMS DLR dispatch={} status={}", row.id, status)
        return Outcome.ACCEPTED
    }

    private fun outcome(outcome: Outcome): Outcome {
        meterRegistry.counter("brainbox.sms.dlr", "outcome", outcome.name.lowercase()).increment()
        return outcome
    }

    private fun secretMatches(secret: String?): Boolean {
        if (secret == null) return false
        return MessageDigest.isEqual(
            secret.toByteArray(StandardCharsets.UTF_8),
            properties.dlrSecret.toByteArray(StandardCharsets.UTF_8),
        )
    }

    private fun classify(raw: String): DlrStatus {
        val text = raw.lowercase().filter(Char::isLetterOrDigit)
        if (text.isEmpty()) return DlrStatus.UNKNOWN
        if (FAILED_WORDS.any { text.contains(it) }) return DlrStatus.FAILED
        if (DELIVERED_WORDS.any { text.contains(it) }) return DlrStatus.DELIVERED
        return DlrStatus.UNKNOWN
    }

    /** Finds the first value for the key list, preferring an exact key match over a nested one. */
    private fun findText(node: JsonNode, keys: List<String>, depth: Int = 0): String? {
        if (depth > MAX_DEPTH) return null
        for (key in keys) {
            val direct = directValue(node, key)
            if (direct != null) return direct
        }
        val children = when {
            node.isArray -> (0 until node.size()).map { node.get(it) }
            node.isObject -> node.properties().map { it.value }
            else -> emptyList()
        }
        for (child in children) findText(child, keys, depth + 1)?.let { return it }
        return null
    }

    private fun directValue(node: JsonNode, wanted: String): String? {
        if (!node.isObject) return null
        for (entry in node.properties()) {
            if (normalize(entry.key) != wanted) continue
            val text = entry.value.asString("").trim()
            if (text.isNotEmpty()) return text
        }
        return null
    }

    private fun normalize(key: String): String = key.lowercase().filter(Char::isLetterOrDigit)

    private companion object {
        val log = LoggerFactory.getLogger(AttendanceSmsDlrService::class.java)
        const val MAX_DEPTH = 3

        /** Ordered: a specific message id beats a generic "id". */
        val MESSAGE_ID_KEYS = listOf(
            "messageid",
            "providermessageid",
            "smsid",
            "cmid",
            "transactionid",
            "requestid",
            "id",
        )
        val STATUS_KEYS = listOf("status", "deliverystatus", "dlrstatus", "statuscode", "state")
        val FAILED_WORDS = listOf("fail", "undeliver", "notdeliver", "reject", "error", "expired", "invalid")
        val DELIVERED_WORDS = listOf("deliver", "success", "accepted", "received")
    }
}
