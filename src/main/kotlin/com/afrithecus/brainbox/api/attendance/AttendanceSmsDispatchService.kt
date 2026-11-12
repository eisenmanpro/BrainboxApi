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
import com.afrithecus.brainbox.api.sms.SmsGateway
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
 * Attendance SMS dispatch worker (P3.4). Scans today's ABSENT rows after register close,
 * classifies each recipient through [AttendanceSmsPolicy], reserves budget and a dispatch row
 * transactionally, then makes one provider attempt outside the transaction and records the
 * outcome. Nothing here is called from register submit: a provider outage can never fail a
 * teacher's register, and the scheduler catches every exception.
 *
 *  - exactly-once: the unique (record, recipient) dispatch row plus the phone/day check
 *  - one per phone per day: a guardian shared by siblings receives at most one message today
 *  - budget: drawn down in the reservation transaction, never below zero
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
    @Value("\${app.school-zone:Africa/Nairobi}") schoolZoneId: String,
) {

    private val zone: ZoneId = ZoneId.of(schoolZoneId)

    /** One sweep; returns the number of provider attempts made. Manual tests call this directly. */
    fun dispatchDue(now: Instant = clock.instant()): Int {
        if (!properties.enabled) return 0
        val day = LocalDate.ofInstant(now, zone)
        var attempted = 0
        for (record in recordRepository.findAllByAttendanceDateAndStatus(day, AttendanceStatus.ABSENT)) {
            val learner = userRepository.findById(record.studentId).orElse(null) ?: continue
            for (recipient in recipients(learner)) {
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
                // A unique-index race with another node rolls back only this reservation; the sweep
                // continues with the next record instead of aborting the whole pass.
                val reservation = runCatching { reserve(record, learner, recipient, phone, day, now) }
                    .getOrElse { failure ->
                        log.warn("Attendance SMS reservation failed record={} reason={}", record.id, failure.message)
                        null
                    } ?: continue
                deliver(reservation)
                attempted++
            }
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

    private fun reserve(
        record: AttendanceRecordEntity,
        learner: UserEntity,
        recipient: UserEntity,
        phone: String,
        day: LocalDate,
        now: Instant,
    ): Reservation? = transactionTemplate.execute<Reservation?> {
        if (dispatchRepository.existsByAttendanceRecordIdAndRecipientUserId(record.id, recipient.id)) {
            return@execute null
        }
        // The teacher may have corrected the row between the scan and this reservation.
        val current = recordRepository.findById(record.id).orElse(null)
        if (current == null || current.status != AttendanceStatus.ABSENT) return@execute null

        val dayStart = day.atStartOfDay(zone).toInstant()
        val dayEnd = day.plusDays(1).atStartOfDay(zone).toInstant()
        if (dispatchRepository.existsByPhoneNumberAndCreatedAtBetween(phone, dayStart, dayEnd)) {
            return@execute null
        }

        val schoolId = record.schoolId ?: learner.schoolId ?: return@execute null
        val term = AttendanceTerm.label(day)
        val budget = budgetRepository.findBySchoolIdAndTermLabel(schoolId, term)
            ?: SchoolSmsBudgetEntity().apply {
                this.schoolId = schoolId
                this.termLabel = term
                this.budgetKes = properties.budgetDefaultKes
            }
        if (budget.remainingKes < properties.costPerSmsKes) {
            alertLowBudget(budget, schoolId, term, now)
            return@execute null
        }
        budget.usedKes = budget.usedKes.add(properties.costPerSmsKes)
        budget.unitsSent += 1
        budgetRepository.save(budget)

        val schoolName = schoolRepository.findById(schoolId).map { it.name }.orElse(null)
        val dispatch = AttendanceSmsDispatchEntity().apply {
            this.attendanceRecordId = record.id
            this.schoolId = schoolId
            this.learnerId = learner.id
            this.recipientUserId = recipient.id
            this.phoneNumber = phone
            this.status = AttendanceSmsStatus.PENDING
            this.provider = gateway.gateway().provider
            this.units = 1
            this.costKes = properties.costPerSmsKes
        }
        dispatchRepository.saveAndFlush(dispatch)
        Reservation(dispatch.id, schoolId, phone, policy.message(learner, day, schoolName), dispatch.provider.orEmpty())
    }

    private fun deliver(reservation: Reservation) {
        val providerId = try {
            gateway.gateway().send(reservation.phone, reservation.message)
        } catch (failure: Exception) {
            log.warn(
                "Attendance SMS failed dispatch={} school={} provider={} reason={}",
                reservation.dispatchId,
                reservation.schoolId,
                reservation.provider,
                failure.message,
            )
            complete(reservation.dispatchId, AttendanceSmsStatus.FAILED, null, failure.message)
            return
        }
        log.info(
            "Attendance SMS sent dispatch={} school={} provider={} providerMessageId={}",
            reservation.dispatchId,
            reservation.schoolId,
            reservation.provider,
            providerId,
        )
        complete(reservation.dispatchId, AttendanceSmsStatus.SENT, providerId, null)
    }

    private fun complete(dispatchId: UUID, status: AttendanceSmsStatus, providerId: String?, error: String?) {
        transactionTemplate.executeWithoutResult {
            val row = dispatchRepository.findById(dispatchId).orElse(null) ?: return@executeWithoutResult
            // A row that is no longer PENDING was finalised by another node; never overwrite it.
            if (row.status != AttendanceSmsStatus.PENDING) return@executeWithoutResult
            row.status = status
            row.providerMessageId = providerId?.take(160)
            row.error = error?.take(512)
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
