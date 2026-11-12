package com.afrithecus.brainbox.api.attendance.web

import java.math.BigDecimal

/** One school's D5 allocation and spend for a term, as the console and school panels see it. */
data class AttendanceSmsSchoolSummary(
    val schoolId: String,
    val schoolName: String,
    val term: String,
    /** Money is a decimal string so a JSON number never rounds a cent away. */
    val budgetKes: String,
    val usedKes: String,
    val remainingKes: String,
    val smsSent: Long,
    val smsFailed: Long,
    val lowBalance: Boolean,
)

/** One dispatch in the ledger. The phone is masked; no credential or full number is exposed. */
data class AttendanceSmsDispatchView(
    val id: String,
    val createdAt: Long,
    val status: String,
    val provider: String?,
    val learnerName: String,
    val phoneMasked: String,
    val costKes: String,
    val error: String?,
)

/** Platform console: set a school's term allocation. */
data class UpdateAttendanceSmsBudgetRequest(
    val budgetKes: BigDecimal,
)
