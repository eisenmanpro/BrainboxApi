package com.afrithecus.brainbox.api.attendance

import com.afrithecus.brainbox.api.attendance.model.AttendanceSmsStatus
import com.afrithecus.brainbox.api.attendance.repository.AttendanceSmsDispatchRepository
import com.afrithecus.brainbox.api.attendance.repository.SchoolSmsBudgetRepository
import com.afrithecus.brainbox.api.attendance.web.AttendanceSmsDispatchView
import com.afrithecus.brainbox.api.attendance.web.AttendanceSmsSchoolSummary
import com.afrithecus.brainbox.api.common.error.ApiErrorCode
import com.afrithecus.brainbox.api.common.error.ApiException
import com.afrithecus.brainbox.api.common.error.invalidArgument
import com.afrithecus.brainbox.api.common.error.notFound
import com.afrithecus.brainbox.api.identity.PlatformAccessService
import com.afrithecus.brainbox.api.identity.PlatformPermission
import com.afrithecus.brainbox.api.identity.entity.SchoolEntity
import com.afrithecus.brainbox.api.identity.model.CurrentUser
import com.afrithecus.brainbox.api.identity.model.Role
import com.afrithecus.brainbox.api.identity.model.SubRole
import com.afrithecus.brainbox.api.identity.repository.SchoolRepository
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * P3.5: read/write surface for the attendance SMS spend. The platform console reads every school
 * (SCHOOLS_READ) and sets an allocation (SCHOOLS_MANAGE); a school's ICT admin sees only their own
 * school. Payloads carry masked phones and no credentials, and money is a decimal string.
 */
@Service
class AttendanceSmsAdminService(
    private val properties: AttendanceSmsProperties,
    private val budgets: SchoolSmsBudgetRepository,
    private val dispatches: AttendanceSmsDispatchRepository,
    private val users: UserRepository,
    private val schools: SchoolRepository,
    private val access: PlatformAccessService,
    @Value("\${app.school-zone:Africa/Nairobi}") private val schoolZoneId: String,
) {

    private val zone: ZoneId get() = ZoneId.of(schoolZoneId)
    private val currentTerm: String get() = AttendanceTerm.label(LocalDate.now(zone))

    @Transactional(readOnly = true)
    fun consoleSchools(current: CurrentUser, term: String?): List<AttendanceSmsSchoolSummary> {
        access.requirePermission(current, PlatformPermission.SCHOOLS_READ)
        val label = term?.trim()?.takeIf { it.isNotEmpty() } ?: currentTerm
        return budgets.findAllByTermLabel(label).map { budget ->
            summary(budget.schoolId, label)
        }.sortedBy { it.schoolName }
    }

    @Transactional(readOnly = true)
    fun consoleSchool(current: CurrentUser, schoolIdRaw: String, term: String?): AttendanceSmsSchoolSummary {
        access.requirePermission(current, PlatformPermission.SCHOOLS_READ)
        val label = term?.trim()?.takeIf { it.isNotEmpty() } ?: currentTerm
        return summary(parseSchool(schoolIdRaw), label)
    }

    @Transactional
    fun updateBudget(
        current: CurrentUser,
        schoolIdRaw: String,
        budgetKes: BigDecimal,
        term: String?,
    ): AttendanceSmsSchoolSummary {
        access.requirePermission(current, PlatformPermission.SCHOOLS_MANAGE)
        if (budgetKes.signum() < 0) throw invalidArgument("budgetKes must not be negative")
        if (budgetKes > MAX_BUDGET) throw invalidArgument("budgetKes is unreasonably large")
        val schoolId = parseSchool(schoolIdRaw)
        val label = term?.trim()?.takeIf { it.isNotEmpty() } ?: currentTerm
        val row = budgets.findBySchoolIdAndTermLabel(schoolId, label) ?: newBudget(schoolId, label)
        row.budgetKes = budgetKes.setScale(2)
        // A top-up re-arms the one low-balance alert for this term.
        row.lowBalanceAlertedAt = null
        budgets.save(row)
        return summary(schoolId, label)
    }

    @Transactional(readOnly = true)
    fun dispatchesForSchool(
        current: CurrentUser,
        schoolIdRaw: String,
        term: String?,
        limit: Int,
    ): List<AttendanceSmsDispatchView> {
        access.requirePermission(current, PlatformPermission.SCHOOLS_READ)
        return ledger(parseSchool(schoolIdRaw), term, limit)
    }

    /** School panel: an ICT admin (or a platform admin) reads their own school only. */
    @Transactional(readOnly = true)
    fun schoolSummary(current: CurrentUser, schoolIdRaw: String?): AttendanceSmsSchoolSummary {
        return summary(schoolFor(current, schoolIdRaw), currentTerm)
    }

    @Transactional(readOnly = true)
    fun schoolDispatches(current: CurrentUser, schoolIdRaw: String?, limit: Int): List<AttendanceSmsDispatchView> {
        return ledger(schoolFor(current, schoolIdRaw), null, limit)
    }

    private fun summary(schoolId: UUID, label: String): AttendanceSmsSchoolSummary {
        val school = schools.findById(schoolId).orElse(null) ?: throw notFound("School not found")
        val budget = budgets.findBySchoolIdAndTermLabel(schoolId, label)
        val range = AttendanceTerm.range(label, zone)
        val rows = range?.let {
            dispatches.findAllBySchoolIdAndCreatedAtBetweenOrderByCreatedAtDesc(schoolId, it.start, it.end)
        } ?: emptyList()
        val sent = rows.count { row -> row.status == AttendanceSmsStatus.SENT }.toLong()
        val failed = rows.count { row -> row.status == AttendanceSmsStatus.FAILED }.toLong()
        val budgetKes = budget?.budgetKes ?: properties.budgetDefaultKes
        val usedKes = budget?.usedKes ?: BigDecimal.ZERO
        val remaining = budgetKes.subtract(usedKes)
        return AttendanceSmsSchoolSummary(
            schoolId = schoolId.toString(),
            schoolName = school.name,
            term = label,
            budgetKes = money(budgetKes),
            usedKes = money(usedKes),
            remainingKes = money(remaining),
            smsSent = sent,
            smsFailed = failed,
            lowBalance = remaining <= properties.lowBalanceThresholdKes,
        )
    }

    private fun ledger(schoolId: UUID, term: String?, limit: Int): List<AttendanceSmsDispatchView> {
        val range = term?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { AttendanceTerm.range(it, zone) }
            ?: AttendanceTerm.range(currentTerm, zone)
                ?: return emptyList()
        val rows = dispatches.findAllBySchoolIdAndCreatedAtBetweenOrderByCreatedAtDesc(
            schoolId,
            range.start,
            range.end,
        ).take(limit.coerceIn(1, MAX_LEDGER))
        val names = users.findAllById(rows.map { it.learnerId }.distinct())
            .associate { it.id to it.name }
        return rows.map { row ->
            AttendanceSmsDispatchView(
                id = row.id.toString(),
                createdAt = row.createdAt.toEpochMilli(),
                status = row.status.name,
                provider = row.provider,
                learnerName = names[row.learnerId] ?: "Learner",
                phoneMasked = mask(row.phoneNumber),
                costKes = money(row.costKes),
                error = row.error,
            )
        }
    }

    private fun schoolFor(current: CurrentUser, schoolIdRaw: String?): UUID {
        val user = users.findById(current.userId).orElse(null) ?: throw notFound("User not found")
        val requested = schoolIdRaw?.trim()?.takeIf { it.isNotEmpty() }?.let(::parseSchool)
        if (user.role == Role.ADMIN) {
            return requested ?: user.schoolId ?: throw invalidArgument("schoolId is required")
        }
        if (user.subRole != SubRole.ICT_ADMIN) {
            throw ApiException(ApiErrorCode.FORBIDDEN, "Only a school's ICT admin can read the SMS ledger")
        }
        val own = user.schoolId ?: throw ApiException(ApiErrorCode.FORBIDDEN, "You are not attached to a school")
        if (requested != null && requested != own) {
            throw ApiException(ApiErrorCode.FORBIDDEN, "You can only read your own school")
        }
        return own
    }

    private fun newBudget(schoolId: UUID, label: String) = com.afrithecus.brainbox.api.attendance.entity.SchoolSmsBudgetEntity().apply {
        this.schoolId = schoolId
        this.termLabel = label
        this.budgetKes = properties.budgetDefaultKes
    }

    private fun parseSchool(raw: String): UUID =
        runCatching { UUID.fromString(raw.trim()) }.getOrNull()
            ?: throw invalidArgument("schoolId is not a valid identifier")

    private fun mask(phone: String): String {
        val digits = phone.filter(Char::isDigit)
        return if (digits.length <= 4) "***" else "***" + digits.takeLast(4)
    }

    private fun money(value: BigDecimal): String = value.setScale(2).toPlainString()

    private companion object {
        const val MAX_LEDGER = 200
        val MAX_BUDGET: BigDecimal = BigDecimal("1000000.00")
    }
}
