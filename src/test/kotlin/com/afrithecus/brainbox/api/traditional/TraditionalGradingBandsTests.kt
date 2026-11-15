package com.afrithecus.brainbox.api.traditional

import com.afrithecus.brainbox.api.common.error.ApiException
import com.afrithecus.brainbox.api.traditional.web.GradingBandDto
import com.afrithecus.brainbox.api.traditional.web.GradingConfigDefaults
import com.afrithecus.brainbox.api.traditional.web.GradingConfigDto
import com.afrithecus.brainbox.api.traditional.web.OverallGradingBandDto
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * Pure unit tests for the session band rules (D18/decision 20) and the mean-percentage
 * overall level (D17/G1.4). No Spring context: these are the rules the REST tests assume.
 */
class TraditionalGradingBandsTests {

    private fun bands(vararg pairs: Pair<String, Int>) = GradingConfigDto(
        bands = pairs.map { GradingBandDto(it.first, it.second) },
    )

    @Test
    fun `platform defaults are valid and normalised`() {
        val resolved = TraditionalGradingBands.validateAndNormalize(GradingConfigDto(GradingConfigDefaults.bands()))
        assertEquals(GradingConfigDefaults.bands(), resolved.bands)
        assertNull(resolved.overallBands)
    }

    @Test
    fun `ascending input is normalised into strictly descending thresholds`() {
        val resolved = TraditionalGradingBands.validateAndNormalize(
            bands("E" to 0, "AE" to 50, "EE" to 80, "ME" to 65),
        )
        assertEquals(listOf("EE" to 80, "ME" to 65, "AE" to 50, "E" to 0), resolved.bands.map { it.grade to it.minPercentage })
    }

    @Test
    fun `thresholds outside 0 to 100 are rejected`() {
        assertFailsWith<ApiException> { TraditionalGradingBands.validateAndNormalize(bands("EE" to 120, "E" to 0)) }
        assertFailsWith<ApiException> { TraditionalGradingBands.validateAndNormalize(bands("EE" to 80, "E" to -5)) }
    }

    @Test
    fun `duplicate thresholds and labels are rejected`() {
        assertFailsWith<ApiException> { TraditionalGradingBands.validateAndNormalize(bands("EE" to 80, "ME" to 80, "E" to 0)) }
        assertFailsWith<ApiException> { TraditionalGradingBands.validateAndNormalize(bands("EE" to 80, "ee" to 60, "E" to 0)) }
        assertFailsWith<ApiException> { TraditionalGradingBands.validateAndNormalize(bands(" " to 80, "E" to 0)) }
        assertFailsWith<ApiException> { TraditionalGradingBands.validateAndNormalize(GradingConfigDto(bands = emptyList())) }
    }

    @Test
    fun `overall raw bands are validated as unique non-negative thresholds`() {
        assertFailsWith<ApiException> {
            TraditionalGradingBands.validateAndNormalize(
                GradingConfigDto(
                    bands = GradingConfigDefaults.bands(),
                    overallBands = listOf(OverallGradingBandDto("EE", 350), OverallGradingBandDto("ME", 350)),
                ),
            )
        }
        assertFailsWith<ApiException> {
            TraditionalGradingBands.validateAndNormalize(
                GradingConfigDto(bands = GradingConfigDefaults.bands(), overallBands = listOf(OverallGradingBandDto("EE", -1))),
            )
        }
        val cleared = TraditionalGradingBands.validateAndNormalize(
            GradingConfigDto(bands = GradingConfigDefaults.bands(), overallBands = emptyList()),
        )
        assertNull(cleared.overallBands)
    }

    @Test
    fun `subject percentage bands at the boundaries`() {
        val config = GradingConfigDto()
        assertEquals("EE", TraditionalGradingBands.band(80.0, config))
        assertEquals("ME", TraditionalGradingBands.band(79.9, config))
        assertEquals("ME", TraditionalGradingBands.band(65.0, config))
        assertEquals("DE", TraditionalGradingBands.band(20.0, config))
        assertEquals("E", TraditionalGradingBands.band(19.9, config))
        assertEquals("E", TraditionalGradingBands.band(0.0, config))
    }

    @Test
    fun `overall level derives from the mean percentage not the raw total`() {
        val config = GradingConfigDto()
        // G1.4 reproduction: an eight-subject total of 356 is 44.5 %, which the old raw bands
        // (EE >= 350) called "EE"; the mean percentage is BE under the default bands.
        assertEquals(44.5, TraditionalGradingBands.meanPercentage(356.0, 8), 0.0001)
        assertEquals("BE", TraditionalGradingBands.bandForMean(356.0, 8, config))
        // The same total on three subjects is 118.7 %-worth of marks -> capped by band logic at EE.
        assertEquals("EE", TraditionalGradingBands.bandForMean(356.0, 3, config))
        // A perfect three-subject total is EE, where the raw rule happened to agree.
        assertEquals("EE", TraditionalGradingBands.bandForMean(300.0, 3, config))
        // No subject count has no mean.
        assertEquals(0.0, TraditionalGradingBands.meanPercentage(120.0, 0), 0.0001)
        assertEquals("E", TraditionalGradingBands.bandForMean(120.0, 0, config))
    }

    @Test
    fun `custom session bands change the overall level`() {
        val split = bands("A" to 50, "B" to 0)
        // 70 % mean is A under the school's 50/0 split, ME under the platform defaults.
        assertEquals("A", TraditionalGradingBands.bandForMean(140.0, 2, split))
        assertEquals("ME", TraditionalGradingBands.bandForMean(140.0, 2, GradingConfigDto()))
    }
}
