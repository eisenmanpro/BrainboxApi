package com.afrithecus.brainbox.api.attendance

import com.afrithecus.brainbox.api.attendance.entity.AttendanceRecordEntity
import com.afrithecus.brainbox.api.attendance.entity.AttendanceSmsDispatchEntity
import com.afrithecus.brainbox.api.attendance.entity.SchoolSmsBudgetEntity
import com.afrithecus.brainbox.api.attendance.model.AttendanceSmsStatus
import com.afrithecus.brainbox.api.attendance.model.AttendanceStatus
import com.afrithecus.brainbox.api.attendance.repository.AttendanceRecordRepository
import com.afrithecus.brainbox.api.attendance.repository.AttendanceSmsDispatchRepository
import com.afrithecus.brainbox.api.attendance.repository.SchoolSmsBudgetRepository
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.model.Role
import com.afrithecus.brainbox.api.identity.model.SubRole
import com.afrithecus.brainbox.api.identity.repository.SchoolRepository
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import com.afrithecus.brainbox.api.notification.NotificationService
import com.afrithecus.brainbox.api.notification.model.NotificationPriority
import com.afrithecus.brainbox.api.notification.model.NotificationType
import com.afrithecus.brainbox.api.notification.model.NotificationUrgency
import com.afrithecus.brainbox.api.sms.AttendanceSmsGateway
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * Attendance SMS dispatch worker (P3.4) extended by Phase 4:
 *
 *  - P4.1 read-ack: the policy decides when a push token holder's unread row justifies a fallback
 *  - P4.2 digest: all absent children of one family/phone/school are listed in one paid message;
 *    the extra records keep a MERGED ledger row with no cost and no provider call
 *  - P4.4 chain: delivery may move to a fallback provider; the row records the provider that
 *    actually accepted the message
 *
 * Scans today's ABSENT rows after register close, classifies each recipient through
 * [AttendanceSmsPolicy], reserves budget and the dispatch rows transactionally, then makes one
 * delivery attempt outside the transaction. Nothing here is called from register submit: a
 * provider outage can never fail a teacher's register, and the scheduler catches every exception.
 *
 *  - exactly-once: the unique (record, recipient) dispatch row plus the phone/day check
 *  - one paid message per phone per day; siblings are merged into it
 *  - budget: drawn down once per paid message, never below zero
 *  - no retry: a failed attempt stays FAILED; a duplicate SMS costs money and is not safe
 */
@Service
class AttendanceSmsDispatchService(
    private val properties: AttendanceSmsProperties,
    private val policy: AttendanceSmsPolicy,
    private val gateway: AttendanceSmsGateway,
    private val recordRepository: AttendanceRecordRepository,
    private val userRepository: UserRepository,
    private val schoolRepository: SchoolRepository,
    private val dispatchRepository: AttendanceSmsDispatchRepository,
    private val budgetRepository: SchoolSmsBudgetRepository,
    private val notificationService: NotificationService,
    private val transactionTemplate: TransactionTemplate,
    private val clock: Clock,
    @Value("\${app.school-zone:Africa/Nairobi}") private val schoolZoneId: String,
) {

    private val zone: ZoneId = ZoneId.of(schoolZoneId)

    /** One sweep; returns the number of provider attempts made. Manual tests call this directly. */
    fun dispatchDue(now: Instant = clock.instant()): Int {
        if (!properties.enabled) return 0
        val day = LocalDate.ofInstant(now, zone)
        val groups = LinkedHashMap<Pair<UUID, String>, MutableList<Candidate>>()
        for (record in recordRepository.findAllByAttendanceDateAndStatus(day, AttendanceStatus.ABSENT)) {
            val learner = userRepository.findById(record.studentId).orElse(null) ?: continue
            val recipient = recipients(learner).firstOrNull() ?: continue
            val decision = policy.decide(learner, recipient, record, now)
            if (!decision.eligible) {
                log.debug(
                    "Attendance SMS skipped record={} recipient={} reason={}",
                    record.id,
                    recipient.id,
                    decision.skipReason,
                )
                continue
            }
            val phone = decision.phone ?: continue
            val schoolId = record.schoolId ?: learner.schoolId ?: continue
            groups.getOrPut(schoolId to phone) { mutableListOf() } += Candidate(record, learner, recipient, phone)
        }
        var attempted = 0
        for ((key, group) in groups) {
            // A unique-index race with another node rolls back only this reservation; the sweep
            // continues with the next family instead of aborting the whole pass.
            val reservation = runCatching { reserve(key.first, key.second, group, day, now) }
                .getOrElse { failure ->
                    log.warn("Attendance SMS reservation failed family={} reason={}", key.second, failure.message)
                    null
                } ?: continue
            deliver(reservation)
            attempted++
        }
        return attempted
    }

    /**
     * One family inbox, not two: a legacy PARENT alias when the learner has one (pre-F9 data),
     * otherwise the learner's own account, which under F9 IS the guardian account. Sending to both
     * would bill the same human twice, so the paid channel deliberately does not mirror the free
     * fan-out's recipient set.
     */
    private fun recipients(learner: UserEntity): List<UserEntity> {
        val alias = learner.parentUserId?.let { id -> userRepository.findById(id).orElse(null) }
        return listOf(alias ?: learner)
    }

    /**
     * P4.2: reserves one paid message for the family/phone/day and a MERGED row for every extra
     * sibling record, in one transaction. The budget is drawn once; replay of the same group finds
     * the rows and does nothing.
     */
    private fun reserve(
        schoolId: UUID,
        phone: String,
        group: List<Candidate>,
        day: LocalDate,
        now: Instant,
    ): Reservation? = transactionTemplate.execute<Reservation?> {
        val remaining = group.filter { candidate ->
            !dispatchRepository.existsByAttendanceRecordIdAndRecipientUserId(
                candidate.record.id,
                candidate.recipient.id,
            ) && isStillAbsent(candidate.record.id)
        }
        if (remaining.isEmpty()) return@execute null

        val dayStart = day.atStartOfDay(zone).toInstant()
        val dayEnd = day.plusDays(1).atStartOfDay(zone).toInstant()
        if (dispatchRepository.existsByPhoneNumberAndCreatedAtBetween(phone, dayStart, dayEnd)) {
            return@execute null
        }

        val budget = budgetRepository.findBySchoolIdAndTermLabel(schoolId, AttendanceTerm.label(day))
            ?: SchoolSmsBudgetEntity().apply {
                this.schoolId = schoolId
                this.termLabel = AttendanceTerm.label(day)
                this.budgetKes = properties.budgetDefaultKes
            }
        if (budget.remainingKes < properties.costPerSmsKes) {
            alertLowBudget(budget, schoolId, AttendanceTerm.label(day), now)
            return@execute null
        }
        budget.usedKes = budget.usedKes.add(properties.costPerSmsKes)
        budget.unitsSent += 1
        budgetRepository.save(budget)

        val schoolName = schoolRepository.findById(schoolId).map { it.name }.orElse(null)
        val names = remaining.map { it.learner.name }.distinct()
        val message = if (properties.digestEnabled && names.size > 1) {
            policy.digestMessage(names, day, schoolName)
        } else {
            policy.message(remaining.first().learner, day, schoolName)
        }
        val primaryProvider = gateway.chain().primaryProvider
        val first = remaining.first()
        val dispatch = newDispatch(first, schoolId, phone, AttendanceSmsStatus.PENDING, 1, properties.costPerSmsKes, primaryProvider)
        dispatchRepository.saveAndFlush(dispatch)
        for (extra in remaining.drop(1)) {
            dispatchRepository.save(
                newDispatch(extra, schoolId, phone, AttendanceSmsStatus.MERGED, 0, BigDecimal.ZERO, primaryProvider),
            )
        }
        Reservation(dispatch.id, schoolId, phone, message, primaryProvider)
    }

    private fun newDispatch(
        candidate: Candidate,
        schoolId: UUID,
        phone: String,
        status: AttendanceSmsStatus,
        units: Int,
        costKes: BigDecimal,
        provider: String,
    ) = AttendanceSmsDispatchEntity().apply {
        this.attendanceRecordId = candidate.record.id
        this.schoolId = schoolId
        this.learnerId = candidate.learner.id
        this.recipientUserId = candidate.recipient.id
        this.phoneNumber = phone
        this.status = status
        this.provider = provider
        this.units = units
        this.costKes = costKes
    }

    private fun isStillAbsent(recordId: UUID): Boolean =
        recordRepository.findById(recordId).orElse(null)?.status == AttendanceStatus.ABSENT

    private fun deliver(reservation: Reservation) {
        val delivery = try {
            gateway.chain().deliver(reservation.phone, reservation.message)
        } catch (failure: Exception) {
            log.warn(
                "Attendance SMS failed dispatch={} school={} provider={} reason={}",
                reservation.dispatchId,
                reservation.schoolId,
                reservation.provider,
                failure.message,
            )
            complete(reservation.dispatchId, AttendanceSmsStatus.FAILED, null, failure.message, null)
            return
        }
        log.info(
            "Attendance SMS sent dispatch={} school={} provider={} providerMessageId={}",
            reservation.dispatchId,
            reservation.schoolId,
            delivery.provider,
            delivery.messageId,
        )
        complete(reservation.dispatchId, AttendanceSmsStatus.SENT, delivery.messageId, null, delivery.provider)
    }

    private fun complete(
        dispatchId: UUID,
        status: AttendanceSmsStatus,
        providerId: String?,
        error: String?,
        provider: String?,
    ) {
        transactionTemplate.executeWithoutResult {
            val row = dispatchRepository.findById(dispatchId).orElse(null) ?: return@executeWithoutResult
            // A row that is no longer PENDING was finalised by another node; never overwrite it.
            if (row.status != AttendanceSmsStatus.PENDING) return@executeWithoutResult
            row.status = status
            row.providerMessageId = providerId?.take(160)
            row.error = error?.take(512)
            if (provider != null) row.provider = provider
            dispatchRepository.save(row)
        }
    }

    /**
     * One neutral low-balance in-app alert per school/term. The alert is deliberately not an SMS
     * and carries no phone number; the platform console's audit trail keeps the operator action.
     */
    private fun alertLowBudget(budget: SchoolSmsBudgetEntity, schoolId: UUID, term: String, now: Instant) {
        if (budget.lowBalanceAlertedAt != null) return
        budget.lowBalanceAlertedAt = now
        budgetRepository.save(budget)
        val admins = userRepository.findAllBySchoolIdAndIsActiveTrueOrderByNameAsc(schoolId)
            .filter { it.subRole == SubRole.ICT_ADMIN || it.role == Role.ADMIN }
        for (admin in admins) {
            notificationService.notifyUser(
                userId = admin.id,
                title = "Attendance SMS balance low",
                message = "The school's attendance SMS balance for " + term + " is " +
                    budget.remainingKes.setScale(2) + " KES of " + budget.budgetKes.setScale(2) +
                    " KES. Push and in-app absence alerts continue; SMS resumes when the allocation is topped up.",
                type = NotificationType.SYSTEM,
                urgency = NotificationUrgency.HIGH,
                priority = NotificationPriority.HIGH,
                metadata = mapOf(
                    "schoolId" to schoolId.toString(),
                    "term" to term,
                    "remainingKes" to budget.remainingKes.setScale(2).toString(),
                    "budgetKes" to budget.budgetKes.setScale(2).toString(),
                    "budgetAlert" to "true",
                ),
            )
        }
        log.warn(
            "Attendance SMS budget exhausted school={} term={} remaining={} budget={}",
            schoolId,
            term,
            budget.remainingKes.setScale(2),
            budget.budgetKes.setScale(2),
        )
    }

    private data class Candidate(
        val record: AttendanceRecordEntity,
        val learner: UserEntity,
        val recipient: UserEntity,
        val phone: String,
    )

    private data class Reservation(
        val dispatchId: UUID,
        val schoolId: UUID,
        val phone: String,
        val message: String,
        val provider: String,
    )

    private companion object {
        val log = LoggerFactory.getLogger(AttendanceSmsDispatchService::class.java)
    }
}
