package com.afrithecus.brainbox.api.traditional

import com.afrithecus.brainbox.api.traditional.repository.TraditionalGradingConfigRepository
import com.afrithecus.brainbox.api.traditional.web.GradingBandDto
import com.afrithecus.brainbox.api.traditional.web.GradingConfigDefaults
import com.afrithecus.brainbox.api.traditional.web.GradingConfigDto
import com.afrithecus.brainbox.api.traditional.web.OverallGradingBandDto
import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper
import java.util.UUID

/**
 * Resolves the coordinator-configured grading bands for a grade, with the CBC
 * defaults as fallback. Shared by the traditional exam reports and the gradebook
 * so every surface bands a percentage the same way.
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

    fun band(percentage: Double, config: GradingConfigDto): String {
        config.bands.sortedByDescending { it.minPercentage }.forEach { if (percentage >= it.minPercentage) return it.grade }
        return config.bands.minByOrNull { it.minPercentage }?.grade ?: "E"
    }

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
