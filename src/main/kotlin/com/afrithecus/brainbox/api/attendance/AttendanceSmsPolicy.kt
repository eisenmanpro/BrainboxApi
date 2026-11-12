package com.afrithecus.brainbox.api.attendance

import com.afrithecus.brainbox.api.attendance.entity.AttendanceRecordEntity
import com.afrithecus.brainbox.api.attendance.model.AttendanceStatus
import com.afrithecus.brainbox.api.auth.PhoneNumbers
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.profile.repository.UserSettingsRepository
import com.afrithecus.brainbox.api.push.repository.DeviceTokenRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * The guarded-fallback rule set from docs/sms_and_attendance_notifications_plan.md section 6,
 * Phase 3 (P3.3). Pure decision: no I/O beyond repositories, no sending. Every rule is asserted
 * independently in AttendanceSmsPolicyTest.
 *
 * Order matters because the cheapest answer wins: the master switch and the school allowlist are
 * checked first, then the event (absent, school day, after register close, inside the window),
 * then the recipient (subscription, phone, verification, consent, capability). A message is paid
 * for only when every gate passes; anything else is a skip with a reason an operator can read.
 */
@Component
class AttendanceSmsPolicy(
    private val properties: AttendanceSmsProperties,
    private val routingPolicy: AttendanceRecipientPolicy,
    private val deviceTokenRepository: DeviceTokenRepository,
    private val userSettingsRepository: UserSettingsRepository,
    @Value("\${app.school-zone:Africa/Nairobi}") private val schoolZoneId: String,
) {

    private val zone: ZoneId get() = ZoneId.of(schoolZoneId)

    enum class SkipReason {
        DISABLED,
        SCHOOL_NOT_ALLOWED,

        /** Only ABSENT justifies a paid message (LATE/PRESENT/EXCUSED never do). */
        STATUS_NOT_ABSENT,

        /** Weekend/holiday guard; configurable school-day list. */
        NOT_SCHOOL_DAY,

        /** Before the register-close cutoff: a correction/late arrival can still cancel the send. */
        BEFORE_SEND_AFTER,

        /** Outside the nuisance window. */
        OUTSIDE_WINDOW,

        /** Phase 2 D1: no paid channel for a family whose subscription does not include it. */
        NOT_SUBSCRIBED,

        /** No usable Kenyan number on the account. */
        NO_PHONE,

        /** Unverified numbers waste credits (docs/phone_verification.md). */
        UNVERIFIED_PHONE,

        /** D2: opt-in at first use, never bulk opt-in. */
        NOT_OPTED_IN,

        /** Capability gating: the guardian has a working app, so push already covers this. */
        HAS_PUSH_TOKEN,
    }

    data class Decision(
        val eligible: Boolean,
        val skipReason: SkipReason?,
        /** Canonical local phone ("0712345678") to send to, when eligible. */
        val phone: String? = null,
    )

    fun decide(
        learner: UserEntity,
        recipient: UserEntity,
        record: AttendanceRecordEntity,
        now: Instant,
    ): Decision {
        if (!properties.enabled) return skip(SkipReason.DISABLED)

        val day = LocalDate.ofInstant(now, zone)
        val time = LocalTime.ofInstant(now, zone)

        if (!properties.allowsSchool(learner.schoolId?.toString())) return skip(SkipReason.SCHOOL_NOT_ALLOWED)
        if (record.status != AttendanceStatus.ABSENT) return skip(SkipReason.STATUS_NOT_ABSENT)
        if (properties.schoolDaySet.isNotEmpty() && day.dayOfWeek !in properties.schoolDaySet) {
            return skip(SkipReason.NOT_SCHOOL_DAY)
        }
        if (time.isBefore(properties.windowStartTime) || time.isAfter(properties.windowEndTime)) {
            return skip(SkipReason.OUTSIDE_WINDOW)
        }
        if (time.isBefore(properties.sendAfterTime)) return skip(SkipReason.BEFORE_SEND_AFTER)

        // D1 subscription routing decides the paid channel; free channels are Phase 2's concern.
        if (!routingPolicy.decide(learner, recipient).sms) return skip(SkipReason.NOT_SUBSCRIBED)

        val phone = PhoneNumbers.normalize(recipient.phoneNumber.orEmpty())
            ?: return skip(SkipReason.NO_PHONE)
        if (properties.requireVerifiedPhone && recipient.phoneVerifiedAt == null) {
            return skip(SkipReason.UNVERIFIED_PHONE)
        }
        val optedIn = userSettingsRepository.findByUserId(recipient.id)?.attendanceSmsOptIn
            ?: properties.defaultOptIn
        if (!optedIn) return skip(SkipReason.NOT_OPTED_IN)
        if (deviceTokenRepository.existsByUserId(recipient.id)) return skip(SkipReason.HAS_PUSH_TOKEN)

        return Decision(eligible = true, skipReason = null, phone = phone)
    }

    /** Renders the configured template; GSM-7 only, so one segment (no emoji/accents/links). */
    fun message(learner: UserEntity, day: LocalDate, schoolName: String?): String {
        val rendered = properties.messageTemplate
            .replace("{learner}", learner.name.trim().take(48))
            .replace("{date}", day.format(DATE_FORMAT))
            .replace("{school}", schoolName?.trim()?.take(40) ?: "")
        return rendered
            .filter { it.code in 32..126 }
            .replace(Regex(" +"), " ")
            .trim()
            .take(MAX_SEGMENT)
    }

    private fun skip(reason: SkipReason) = Decision(eligible = false, skipReason = reason)

    private companion object {
        const val MAX_SEGMENT = 160
        val DATE_FORMAT: java.time.format.DateTimeFormatter =
            java.time.format.DateTimeFormatter.ofPattern("d MMM", java.util.Locale.ENGLISH)
    }
}
