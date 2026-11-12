package com.afrithecus.brainbox.api.attendance

import com.afrithecus.brainbox.api.attendance.entity.AttendanceRecordEntity
import com.afrithecus.brainbox.api.attendance.model.AttendanceStatus
import com.afrithecus.brainbox.api.attendance.repository.AttendanceRecordRepository
import com.afrithecus.brainbox.api.auth.PhoneNumbers
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.notification.model.NotificationType
import com.afrithecus.brainbox.api.notification.repository.NotificationRepository
import com.afrithecus.brainbox.api.profile.repository.UserSettingsRepository
import com.afrithecus.brainbox.api.push.repository.DeviceTokenRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * The guarded-fallback rule set from docs/sms_and_attendance_notifications_plan.md section 6,
 * Phase 3 (P3.3) and Phase 4 (P4.1 read-ack, P4.3 risk threshold, P4.6 templates).
 * Pure decision: no sending. Every rule is asserted independently in AttendanceSmsPolicyTest.
 *
 * Order matters because the cheapest answer wins: the master switch and the school allowlist are
 * checked first, then the event (absent, school day, after register close, inside the window),
 * then the recipient (subscription, phone, verification, consent), then the push channel:
 *
 *  - no active push token: the app cannot reach this guardian, so the SMS is the fallback and is
 *    sent as soon as every other gate passes;
 *  - active push token: P4.1 defers the SMS until the in-app absence row has been unread for
 *    [AttendanceSmsProperties.readAckMinutes]. A read row cancels it; no row at all means the push
 *    was not attempted (disabled preference, fan-out failure) and the fallback applies. P4.3 can
 *    additionally require the learner to be at risk when the risk threshold is enabled.
 *
 * Anything that does not pass is a skip with a reason an operator can read.
 */
@Component
class AttendanceSmsPolicy(
    private val properties: AttendanceSmsProperties,
    private val routingPolicy: AttendanceRecipientPolicy,
    private val deviceTokenRepository: DeviceTokenRepository,
    private val userSettingsRepository: UserSettingsRepository,
    private val notificationRepository: NotificationRepository,
    private val recordRepository: AttendanceRecordRepository,
    private val mapper: ObjectMapper,
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

        /** Capability gating (Phase 3 rule, restored when read-ack is disabled). */
        HAS_PUSH_TOKEN,

        /** P4.1: the guardian already read the absence notification, so no SMS is bought. */
        PUSH_ALREADY_READ,

        /** P4.1: the read-ack window has not elapsed yet; the next sweep decides again. */
        AWAITING_READ_ACK,

        /** P4.3: the learner is not at risk enough to justify a fallback for a token holder. */
        BELOW_RISK_THRESHOLD,
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

        if (deviceTokenRepository.existsByUserId(recipient.id)) {
            val pushFallback = evaluatePushHolder(learner, recipient, record, day, now)
            if (pushFallback != null) return pushFallback
        }

        return Decision(eligible = true, skipReason = null, phone = phone)
    }

    /**
     * P4.1/P4.3: returns a skip decision while the token holder must not get a paid SMS, or null
     * when the fallback window has elapsed and the message may be sent.
     */
    private fun evaluatePushHolder(
        learner: UserEntity,
        recipient: UserEntity,
        record: AttendanceRecordEntity,
        day: LocalDate,
        now: Instant,
    ): Decision? {
        if (!properties.readAckEnabled) return skip(SkipReason.HAS_PUSH_TOKEN)
        val notifications = attendanceNotifications(recipient.id, learner.id, day)
        if (notifications.any { it.isRead }) return skip(SkipReason.PUSH_ALREADY_READ)
        val oldest = notifications.minByOrNull { it.createdAt }
        if (oldest != null) {
            val ackDue = oldest.createdAt.plus(Duration.ofMinutes(properties.readAckMinutes.coerceAtLeast(0)))
            if (now.isBefore(ackDue)) return skip(SkipReason.AWAITING_READ_ACK)
        }
        // No row means the push was never attempted (preference off / fan-out failure): the
        // fallback applies immediately, exactly like a guardian with no app.
        if (properties.riskThresholdEnabled && !meetsRiskThreshold(learner, record)) {
            return skip(SkipReason.BELOW_RISK_THRESHOLD)
        }
        return null
    }

    /** In-app ATTENDANCE rows for this learner written to this recipient today. */
    private fun attendanceNotifications(recipientId: java.util.UUID, learnerId: java.util.UUID, day: LocalDate) =
        runCatching {
            val from = day.atStartOfDay(zone).toInstant()
            val to = day.plusDays(1).atStartOfDay(zone).toInstant()
            notificationRepository
                .findAllByUserIdAndTypeAndCreatedAtBetweenOrderByCreatedAtAsc(
                    recipientId,
                    NotificationType.ATTENDANCE,
                    from,
                    to,
                )
                .filter { notification ->
                    runCatching {
                        mapper.readTree(notification.metadata ?: "{}").path("studentId").asString("")
                    }.getOrNull() == learnerId.toString()
                }
        }.getOrDefault(emptyList())

    /**
     * D3 risk signal: today is the second (or later) consecutive ABSENT register row, or the
     * learner's term attendance is at/below the configured percentage. Missing register days are
     * not absences (only filed rows are returned).
     */
    private fun meetsRiskThreshold(learner: UserEntity, record: AttendanceRecordEntity): Boolean {
        val day = record.attendanceDate
        val history = recordRepository.findAllByStudentIdOrderByAttendanceDateDesc(learner.id)
        var consecutive = 1 // today's row is ABSENT by the time this is called
        for (row in history) {
            if (!row.attendanceDate.isBefore(day)) continue
            if (row.status == AttendanceStatus.ABSENT) consecutive++ else break
        }
        if (consecutive >= properties.riskMinConsecutiveAbsences.coerceAtLeast(2)) return true

        val threshold = properties.riskTermAttendancePercent
        if (threshold <= 0.0 || threshold > 100.0) return false
        val range = AttendanceTerm.range(AttendanceTerm.label(day), zone) ?: return false
        val from = LocalDate.ofInstant(range.start, zone)
        val to = LocalDate.ofInstant(range.end, zone)
        val counted = history.filter {
            !it.attendanceDate.isBefore(from) && it.attendanceDate.isBefore(to) &&
                it.status != AttendanceStatus.EXCUSED
        }
        if (counted.isEmpty()) return false
        val attended = counted.count {
            it.status == AttendanceStatus.PRESENT || it.status == AttendanceStatus.LATE
        }
        return attended * 100.0 / counted.size <= threshold
    }

    /** Renders the configured template; GSM-7 only, so one segment (no emoji/accents/links). */
    fun message(learner: UserEntity, day: LocalDate, schoolName: String?): String =
        render(
            if (isSwahili()) properties.messageTemplateSw else properties.messageTemplate,
            mapOf(
                "{learner}" to learner.name.trim().take(48),
                "{date}" to day.format(DATE_FORMAT),
                "{school}" to schoolName?.trim()?.take(40).orEmpty(),
            ),
        )

    /**
     * P4.2: one message listing every absent child of the same family for the day. Names are
     * dropped from the tail until the text fits one GSM-7 segment.
     */
    fun digestMessage(learners: List<String>, day: LocalDate, schoolName: String?): String {
        val names = learners.map(::cleanName).filter { it.isNotEmpty() }.distinct()
        if (names.isEmpty()) return render(digestTemplate(), digestReplacements(emptyList(), 0, day, schoolName))
        var shown = names
        // render(..., cap = false) so the loop can see the real length; the final take caps it.
        var text = render(digestTemplate(), digestReplacements(shown, 0, day, schoolName), cap = false)
        while (text.length > MAX_SEGMENT && shown.size > 1) {
            shown = shown.dropLast(1)
            text = render(digestTemplate(), digestReplacements(shown, names.size - shown.size, day, schoolName), cap = false)
        }
        return text.take(MAX_SEGMENT)
    }

    private fun digestReplacements(
        shown: List<String>,
        omitted: Int,
        day: LocalDate,
        schoolName: String?,
    ): Map<String, String> {
        val listing = shown.joinToString(", ") + if (omitted > 0) " +" + omitted + " more" else ""
        return mapOf(
            "{learners}" to listing,
            "{date}" to day.format(DATE_FORMAT),
            "{school}" to schoolName?.trim()?.take(40).orEmpty(),
        )
    }

    private fun digestTemplate(): String =
        if (isSwahili()) properties.digestTemplateSw else properties.digestTemplate

    private fun isSwahili(): Boolean = properties.language.trim().equals("sw", ignoreCase = true)

    private fun render(template: String, replacements: Map<String, String>, cap: Boolean = true): String {
        var rendered = template
        for ((placeholder, value) in replacements) rendered = rendered.replace(placeholder, value)
        val text = rendered
            .filter { it.code in 32..126 }
            .replace(Regex(" +"), " ")
            .trim()
        return if (cap) text.take(MAX_SEGMENT) else text
    }

    private fun cleanName(name: String): String = name
        .filter { it.code in 32..126 }
        .replace(Regex(" +"), " ")
        .trim()
        .take(NAME_LIMIT)

    private fun skip(reason: SkipReason) = Decision(eligible = false, skipReason = reason)

    private companion object {
        const val MAX_SEGMENT = 160
        const val NAME_LIMIT = 48
        val DATE_FORMAT: java.time.format.DateTimeFormatter =
            java.time.format.DateTimeFormatter.ofPattern("d MMM", java.util.Locale.ENGLISH)
    }
}
