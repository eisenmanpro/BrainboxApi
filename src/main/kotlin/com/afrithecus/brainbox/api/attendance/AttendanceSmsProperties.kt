package com.afrithecus.brainbox.api.attendance

import org.springframework.boot.context.properties.ConfigurationProperties
import java.math.BigDecimal
import java.time.DayOfWeek
import java.time.LocalTime

/**
 * Attendance SMS channel settings (Phase 3, docs/sms_and_attendance_notifications_plan.md sections 6/7).
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
    /** Plain GSM-7, under 160 chars: {learner}, {date}, {school}. */
    val messageTemplate: String =
        "BRAINBOX: {learner} was marked absent on {date}. Contact the school if this is wrong.",
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
