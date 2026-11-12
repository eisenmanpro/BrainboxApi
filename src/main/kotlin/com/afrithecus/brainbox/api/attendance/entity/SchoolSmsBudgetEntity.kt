package com.afrithecus.brainbox.api.attendance.entity

import com.afrithecus.brainbox.api.common.jpa.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * D5: a school's SMS allocation for one term. Created lazily with the configured default on the
 * first send; the used amount is drawn down in the same transaction that reserves a dispatch, so
 * two scheduler instances cannot overdraw it.
 */
@Entity
@Table(name = "school_sms_budgets")
class SchoolSmsBudgetEntity : BaseEntity() {

    @Column(name = "school_id", nullable = false)
    var schoolId: UUID = UUID.randomUUID()

    @Column(name = "term_label", nullable = false, length = 16)
    var termLabel: String = ""

    @Column(name = "budget_kes", nullable = false, precision = 12, scale = 2)
    var budgetKes: BigDecimal = BigDecimal.ZERO

    @Column(name = "used_kes", nullable = false, precision = 12, scale = 2)
    var usedKes: BigDecimal = BigDecimal.ZERO

    @Column(name = "units_sent", nullable = false)
    var unitsSent: Int = 0

    /** One low-balance alert per term; cleared when the allocation is topped up. */
    @Column(name = "low_balance_alerted_at")
    var lowBalanceAlertedAt: Instant? = null

    val remainingKes: BigDecimal get() = budgetKes.subtract(usedKes)
}
