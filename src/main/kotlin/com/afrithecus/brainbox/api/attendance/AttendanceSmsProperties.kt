package com.afrithecus.brainbox.api.attendance

import org.springframework.boot.context.properties.ConfigurationProperties
import java.math.BigDecimal
import java.time.DayOfWeek
import java.time.LocalTime

/**
 * Attendance SMS channel settings (Phases 3-4, docs/sms_and_attendance_notifications_plan.md
 * sections 6/7/15).
 *
 * Everything is off by default: the scheduler bean only exists when [enabled] is true, and the
 * policy refuses every decision while it is false. Credentials are shared with phone
 * verification ([com.afrithecus.brainbox.api.auth.PhoneVerificationProperties]); this block only
 * decides when, to whom and at what cost a message may leave.
 */
@ConfigurationProperties(prefix = "app.attendance.sms")
data class AttendanceSmsProperties(
    /** Master switch. Off means no scheduler and no send. */
    val enabled: Boolean = false,
    /** Gateway name; blank inherits app.auth.phone-verification.provider. */
    val provider: String = "",
    /** Register-close cutoff: no SMS before this local time, so corrections cancel the send. */
    val sendAfter: String = "10:30",
    /** Nuisance window (school zone). Outside it nothing is sent. */
    val windowStart: String = "08:00",
    val windowEnd: String = "17:00",
    /** School days; a Saturday register never produces a paid SMS unless listed here. */
    val schoolDays: List<String> = listOf(
        "MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY",
    ),
    /**
     * Optional school allowlist (UUIDs). Empty means every school once the master switch is on;
     * a non-empty list is the staging/pilot gate.
     */
    val schoolAllowlist: List<String> = emptyList(),
    /** D5 term allocation created lazily for a school's first send. */
    val budgetDefaultKes: BigDecimal = BigDecimal("2000.00"),
    /** Consent default for a user without a settings row (D2: opt-in, so false). */
    val defaultOptIn: Boolean = false,
    /** Paid channel requires a phone that completed the OTP challenge (docs/phone_verification.md). */
    val requireVerifiedPhone: Boolean = true,
    /** HostPinnacle pricing fact 2026-11-12: KES 0.20 per SMS unit. */
    val costPerSmsKes: BigDecimal = BigDecimal("0.20"),
    /** One low-balance in-app alert per school/term when the remaining budget falls below this. */
    val lowBalanceThresholdKes: BigDecimal = BigDecimal("20.00"),

    // ---------------------------------------------------------------- P4.1 read-ack fallback
    /**
     * P4.1: a guardian with a working push token is not skipped outright any more. Their SMS is
     * deferred until the in-app absence row has been unread for [readAckMinutes]; a read row, or
     * a corrected register, cancels the paid message. Off restores the Phase 3 rule (skip every
     * token holder), which is the cheaper but less reliable behaviour.
     */
    val readAckEnabled: Boolean = true,
    /** How long an unread absence notification may sit before the SMS fallback fires. */
    val readAckMinutes: Long = 20,

    // --------------------------------------------------------------------- P4.2 digest
    /**
     * P4.2: one message per family/phone/day. When a guardian has more than one absent child
     * today, the one paid message lists them all instead of merging silently. Off keeps the
     * "first child only" behaviour while still charging one message.
     */
    val digestEnabled: Boolean = true,

    // ---------------------------------------------------------------- P4.3 risk threshold (D3)
    /**
     * P4.3 (D3, default off until measured): for token-holder fallbacks only, require the learner
     * to be at risk before paying for an SMS: a run of consecutive absences or a term attendance
     * rate at/below [riskTermAttendancePercent]. A guardian with no app is always reached.
     */
    val riskThresholdEnabled: Boolean = false,
    /** Consecutive ABSENT school days (today included) that qualify as a risk signal. */
    val riskMinConsecutiveAbsences: Int = 2,
    /** Term attendance percentage at/below which the learner qualifies as at risk; <=0 disables. */
    val riskTermAttendancePercent: Double = 90.0,

    // ---------------------------------------------------------- P4.4 provider fallback chain
    /** Ordered fallback gateways tried when the primary throws; empty keeps the single-provider rule. */
    val fallbackProviders: List<String> = emptyList(),
    /** Consecutive failures that open a provider's circuit. */
    val circuitFailureThreshold: Int = 3,
    /** How long an open circuit skips a provider before it is retried (half-open). */
    val circuitOpenSeconds: Long = 120,

    // ------------------------------------------------------------------- P4.5 DLR webhook
    /** Shared secret for the provider delivery-report webhook; blank disables the endpoint (404). */
    val dlrSecret: String = "",

    // ---------------------------------------------------------------------- P4.6 templates
    /** EN (default) or SW; selects the template pair below. */
    val language: String = "EN",
    /** Plain GSM-7, under 160 chars: {learner}, {date}, {school}. */
    val messageTemplate: String =
        "BRAINBOX: {learner} was marked absent on {date}. Contact the school if this is wrong.",
    /** Swahili counterpart of [messageTemplate]; ASCII only so it stays one GSM-7 segment. */
    val messageTemplateSw: String =
        "BRAINBOX: {learner} hakuwepo shuleni {date}. Wasiliana na shule ikiwa si sahihi.",
    /** P4.2 digest template: {learners}, {date}, {school}. */
    val digestTemplate: String =
        "BRAINBOX: {learners} were absent on {date}. Contact the school if this is wrong.",
    /** Swahili counterpart of [digestTemplate]. */
    val digestTemplateSw: String =
        "BRAINBOX: {learners} hawakuwepo shuleni {date}. Wasiliana na shule ikiwa si sahihi.",

    val scanInitialDelayMs: Long = 600_000,
    val scanMs: Long = 600_000,
) {

    val sendAfterTime: LocalTime get() = parseTime(sendAfter, LocalTime.of(10, 30))
    val windowStartTime: LocalTime get() = parseTime(windowStart, LocalTime.of(8, 0))
    val windowEndTime: LocalTime get() = parseTime(windowEnd, LocalTime.of(17, 0))

    val schoolDaySet: Set<DayOfWeek> get() = schoolDays.mapNotNull { name ->
        runCatching { DayOfWeek.valueOf(name.trim().uppercase()) }.getOrNull()
    }.toSet()

    /** Parsed allowlist; malformed entries are ignored (fail closed, never open). */
    val schoolAllowlistIds: Set<String> get() = schoolAllowlist
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .toSet()

    fun allowsSchool(schoolId: String?): Boolean {
        val allowlist = schoolAllowlistIds
        if (allowlist.isEmpty()) return true
        return schoolId != null && allowlist.contains(schoolId)
    }

    private fun parseTime(raw: String, fallback: LocalTime): LocalTime =
        runCatching { LocalTime.parse(raw.trim()) }.getOrDefault(fallback)
}
