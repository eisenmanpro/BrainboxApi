package com.afrithecus.brainbox.api.analytics

/**
 * Default CBC subject bands. These mirror the documented default GradingConfig
 * (doc 13 §3.1: EE >= 80) and stand in until per-school GradingConfig ships with
 * the traditional exam engine (Phase 4), at which point bands are read per school.
 */
object CbcGrades {

    fun grade(percentage: Double): String = when {
        percentage >= 80.0 -> "EE"
        percentage >= 65.0 -> "ME"
        percentage >= 50.0 -> "AE"
        else -> "BE"
    }

    fun mastery(percentage: Double): String = grade(percentage)

    fun round2(value: Double): Double = Math.round(value * 100.0) / 100.0
}
