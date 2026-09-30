package com.afrithecus.brainbox.api.report

import com.afrithecus.brainbox.api.report.web.ReportType

/**
 * Which report kinds are free and which are metered, plus the server-owned
 * dispatch copy. Policy: docs/ongoing/product_ops_roadmap.md item 1.
 *
 * - FREE_UNLIMITED: aggregate/roster exports (grade analysis, grade combined,
 *   class list and the other class-level tables). Never counted, never blocked.
 * - STUDENT_COUNTED: per-student reports. Each learner covered consumes one unit
 *   of the account's lifetime allowance.
 *
 * The copy lives here so the Android and web clients render identical wording.
 */
enum class ReportCategory { FREE_UNLIMITED, STUDENT_COUNTED }

/** Ledger scope for one download: metered per-student, or free aggregate. */
enum class ReportDownloadScope { FREE, STUDENT }

object ReportPolicy {

    const val DISPATCH_NOTICE: String =
        "Brainbox results are dispatched to student/parents mobile phones once an exam is " +
            "finalized, parents should download from their end."

    fun category(type: ReportType): ReportCategory = when (type) {
        ReportType.CBC_STUDENT,
        ReportType.DETAILED_CBC_STUDENT,
        ReportType.TRADITIONAL_STUDENT,
        -> ReportCategory.STUDENT_COUNTED

        else -> ReportCategory.FREE_UNLIMITED
    }

    fun isStudentCounted(type: ReportType): Boolean = category(type) == ReportCategory.STUDENT_COUNTED

    fun scopeOf(type: ReportType): ReportDownloadScope =
        if (isStudentCounted(type)) ReportDownloadScope.STUDENT else ReportDownloadScope.FREE

    fun freeTypes(): List<ReportType> =
        ReportType.entries.filter { category(it) == ReportCategory.FREE_UNLIMITED }

    fun countedTypes(): List<ReportType> =
        ReportType.entries.filter { category(it) == ReportCategory.STUDENT_COUNTED }
}
