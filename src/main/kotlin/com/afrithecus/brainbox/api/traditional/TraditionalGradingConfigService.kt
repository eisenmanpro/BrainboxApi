package com.afrithecus.brainbox.api.traditional

import com.afrithecus.brainbox.api.traditional.entity.TraditionalGradingConfigEntity
import com.afrithecus.brainbox.api.traditional.repository.TraditionalGradingConfigRepository
import com.afrithecus.brainbox.api.traditional.web.GradingBandDto
import com.afrithecus.brainbox.api.traditional.web.GradingConfigDefaults
import com.afrithecus.brainbox.api.traditional.web.GradingConfigDto
import com.afrithecus.brainbox.api.traditional.web.OverallGradingBandDto
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.util.UUID

/**
 * Resolves and persists the coordinator-configured grading bands for a grade, with
 * the CBC defaults as fallback. Shared by the traditional exam reports, the
 * gradebook and the capture-session bands snapshot (D18) so every surface bands a
 * percentage the same way.
 *
 * Two different reads exist on purpose:
 *  - [configFor] is the **live** grade setting, used by the config screen and the
 *    gradebook.
 *  - [parseSnapshot] is the bands **frozen on an exam** at session creation; marks,
 *    grade bands and reports read it so changing a later grade setting never
 *    re-bands an old exam (decision 20).
 */
@Service
class TraditionalGradingConfigService(
    private val repository: TraditionalGradingConfigRepository,
    private val mapper: ObjectMapper,
) {

    fun configFor(gradeLevel: String, schoolId: UUID?): GradingConfigDto {
        val rows = if (schoolId != null) {
            repository.findAllBySchoolIdAndGradeLevel(schoolId, gradeLevel)
        } else {
            repository.findAllByGradeLevel(gradeLevel)
        }
        val row = rows.firstOrNull() ?: return GradingConfigDto()
        val bands = parseBands(row.bands).ifEmpty { GradingConfigDefaults.bands() }
        return GradingConfigDto(bands, parseOverallBands(row.overallBands))
    }

    /**
     * Resolves the bands a new/edited capture session will use (D18): the coordinator's
     * list when supplied (validated, and written back as the grade's last-used config),
     * otherwise the last-used config for the school+grade, otherwise the platform defaults.
     */
    @Transactional
    fun resolveSessionConfig(
        gradeLevel: String,
        schoolId: UUID?,
        requested: GradingConfigDto?,
    ): GradingConfigDto {
        if (requested == null) return configFor(gradeLevel, schoolId)
        val resolved = TraditionalGradingBands.validateAndNormalize(requested)
        saveConfig(gradeLevel, schoolId, resolved)
        return resolved
    }

    /** Persists the resolved bands as the school+grade last-used config (one row per scope). */
    @Transactional
    fun saveConfig(gradeLevel: String, schoolId: UUID?, config: GradingConfigDto) {
        val normalized = TraditionalGradingBands.validateAndNormalize(config)
        val rows = if (schoolId != null) {
            repository.findAllBySchoolIdAndGradeLevel(schoolId, gradeLevel)
        } else {
            repository.findAllByGradeLevel(gradeLevel)
        }
        val row = rows.firstOrNull() ?: TraditionalGradingConfigEntity().apply {
            this.schoolId = schoolId
            this.gradeLevel = gradeLevel
        }
        row.bands = mapper.writeValueAsString(normalized.bands)
        row.overallBands = normalized.overallBands?.let { mapper.writeValueAsString(it) }
        repository.save(row)
    }

    /** Serialises a resolved band list for the exam's snapshot column (D18). */
    fun snapshot(config: GradingConfigDto): String = mapper.writeValueAsString(config)

    /**
     * Reads an exam's band snapshot. Null when the exam predates the snapshot (a legacy
     * session) or the stored JSON is unreadable; callers then fall back to [configFor].
     */
    fun parseSnapshot(json: String?): GradingConfigDto? {
        if (json.isNullOrBlank()) return null
        return runCatching { mapper.readValue(json, GradingConfigDto::class.java) }
            .getOrNull()
            ?.takeIf { it.bands.isNotEmpty() }
    }

    fun band(percentage: Double, config: GradingConfigDto): String = TraditionalGradingBands.band(percentage, config)

    /**
     * Legacy raw-total banding kept for the pre-D17 rule and the client parity note.
     * Reports no longer use it: overall levels derive from the mean percentage
     * ([TraditionalGradingBands.bandForMean]) because raw thresholds only make sense
     * for one subject count (G1.4).
     */
    fun overallBand(totalScore: Int, config: GradingConfigDto): String {
        val bands = config.overallBands ?: GradingConfigDefaults.overallBands()
        bands.sortedByDescending { it.minRawScore }.forEach { if (totalScore >= it.minRawScore) return it.grade }
        return bands.minByOrNull { it.minRawScore }?.grade ?: "E"
    }

    private fun parseBands(json: String?): List<GradingBandDto> {
        if (json.isNullOrBlank()) return emptyList()
        val node = runCatching { mapper.readTree(json) }.getOrNull() ?: return emptyList()
        if (!node.isArray) return emptyList()
        return (0 until node.size()).mapNotNull { i ->
            val item = node.get(i)
            val grade = item.get("grade")?.asString() ?: return@mapNotNull null
            GradingBandDto(grade, item.get("minPercentage")?.intValue() ?: 0)
        }
    }

    private fun parseOverallBands(json: String?): List<OverallGradingBandDto>? {
        if (json.isNullOrBlank()) return null
        val node = runCatching { mapper.readTree(json) }.getOrNull() ?: return null
        if (!node.isArray) return null
        return (0 until node.size()).mapNotNull { i ->
            val item = node.get(i)
            val grade = item.get("grade")?.asString() ?: return@mapNotNull null
            OverallGradingBandDto(grade, item.get("minRawScore")?.intValue() ?: 0)
        }
    }
}
