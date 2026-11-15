package com.afrithecus.brainbox.api.traditional

import com.afrithecus.brainbox.api.common.error.invalidArgument
import com.afrithecus.brainbox.api.traditional.web.GradingBandDto
import com.afrithecus.brainbox.api.traditional.web.GradingConfigDto
import com.afrithecus.brainbox.api.traditional.web.OverallGradingBandDto
import java.util.Locale

/**
 * Pure grading-band rules for capture sessions (D17/D18, decisions 17/20).
 *
 * Bands are a per-session setting: the coordinator picks them when the capture
 * session is created and the resolved list is snapshotted on the exam. These
 * helpers are deliberately dependency-free so the rules are unit-tested without
 * Spring, and so the same normalisation/validation runs for the session create,
 * the session edit and the grade-config save.
 */
object TraditionalGradingBands {

    /**
     * Validates the coordinator-supplied bands and returns the canonical form
     * (labels trimmed, thresholds sorted strictly descending).
     *
     * Rules (D18): thresholds are percentages in 0..100, labels are non-blank and
     * unique, and the thresholds are unique — i.e. strictly descending once ordered.
     * The input order is not significant; the canonical list is descending.
     *
     * [GradingConfigDto.overallBands] are raw learner totals (Σ subject %), so they
     * are validated as non-negative unique thresholds rather than 0..100. A null or
     * empty list keeps the platform default raw bands for the legacy raw-total rule.
     */
    fun validateAndNormalize(config: GradingConfigDto): GradingConfigDto {
        if (config.bands.isEmpty()) throw invalidArgument("At least one level band is required")
        val seen = mutableSetOf<String>()
        config.bands.forEach { band ->
            val label = band.grade.trim()
            if (label.isEmpty()) throw invalidArgument("Every level band needs a label")
            if (!seen.add(label.uppercase(Locale.ROOT))) {
                throw invalidArgument("Level band labels must be unique: " + label)
            }
            if (band.minPercentage !in 0..100) {
                throw invalidArgument("Level band " + label + " threshold must be between 0 and 100")
            }
        }
        val bands = config.bands.map { GradingBandDto(it.grade.trim(), it.minPercentage) }
            .sortedByDescending { it.minPercentage }
        bands.zipWithNext().forEach { (higher, lower) ->
            if (higher.minPercentage == lower.minPercentage) {
                throw invalidArgument("Level band thresholds must be strictly descending")
            }
        }

        val requestedOverall = config.overallBands?.takeIf { it.isNotEmpty() }
        val overall = requestedOverall?.let { rows ->
            val overallSeen = mutableSetOf<String>()
            rows.forEach { band ->
                val label = band.grade.trim()
                if (label.isEmpty()) throw invalidArgument("Every overall band needs a label")
                if (!overallSeen.add(label.uppercase(Locale.ROOT))) {
                    throw invalidArgument("Overall band labels must be unique: " + label)
                }
                if (band.minRawScore < 0) {
                    throw invalidArgument("Overall band " + label + " threshold must not be negative")
                }
            }
            rows.map { OverallGradingBandDto(it.grade.trim(), it.minRawScore) }
                .sortedByDescending { it.minRawScore }
                .also { sorted ->
                    sorted.zipWithNext().forEach { (higher, lower) ->
                        if (higher.minRawScore == lower.minRawScore) {
                            throw invalidArgument("Overall band thresholds must be strictly descending")
                        }
                    }
                }
        }
        return GradingConfigDto(bands = bands, overallBands = overall)
    }

    /** The band a subject percentage falls in; the highest matching threshold wins. */
    fun band(percentage: Double, config: GradingConfigDto): String {
        config.bands.sortedByDescending { it.minPercentage }.forEach {
            if (percentage >= it.minPercentage) return it.grade
        }
        return config.bands.minByOrNull { it.minPercentage }?.grade ?: "E"
    }

    /**
     * The learner mean the sheets print: TOTAL (Σ subject %) ÷ subject count (§2.5A/B).
     * Zero subjects has no mean; it is reported as 0 so a header can never crash.
     */
    fun meanPercentage(totalPercentage: Double, subjectCount: Int): Double =
        if (subjectCount > 0) totalPercentage / subjectCount else 0.0

    /**
     * D17/G1.4: the overall learner level is the band of the **mean percentage**, not of
     * the raw score total. The old raw thresholds were sized for a five-subject total, so
     * an eight-subject exam printed "44.5 % · Grade EE".
     */
    fun bandForMean(totalPercentage: Double, subjectCount: Int, config: GradingConfigDto): String =
        band(meanPercentage(totalPercentage, subjectCount), config)
}
