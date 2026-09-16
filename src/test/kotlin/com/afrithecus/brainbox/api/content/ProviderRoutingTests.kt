package com.afrithecus.brainbox.api.content

import com.afrithecus.brainbox.api.content.ai.AnswerVerificationRequest
import com.afrithecus.brainbox.api.content.ai.AnswerVerificationResult
import com.afrithecus.brainbox.api.content.ai.AppAiProperties
import com.afrithecus.brainbox.api.content.ai.GenerationRequest
import com.afrithecus.brainbox.api.content.ai.GenerationResult
import com.afrithecus.brainbox.api.content.ai.ProviderCallException
import com.afrithecus.brainbox.api.content.ai.ProviderCostProfile
import com.afrithecus.brainbox.api.content.ai.ProviderHealth
import com.afrithecus.brainbox.api.content.ai.RoutedGenerationProvider
import com.afrithecus.brainbox.api.content.ai.RoutingContentGenerationProvider
import com.afrithecus.brainbox.api.content.ai.RoutingPolicy
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Provider routing by cost and latency (no Spring): the pool order for each
 * policy, in-call failover to the next provider, honest per-attempt failure
 * reporting, and the health demotion that keeps a repeatedly failing provider out
 * of the front of the queue.
 */
class ProviderRoutingTests {

    private val clock: Clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC)

    private fun health(threshold: Int = 3, cooldownSeconds: Long = 120): ProviderHealth =
        ProviderHealth(
            AppAiProperties(routing = AppAiProperties.Routing(failureThreshold = threshold, cooldownSeconds = cooldownSeconds)),
            clock,
        )

    private fun request() = GenerationRequest(
        generationKey = "ke:cbc:grade4:mat:notes:en:v1",
        taskType = "NOTES",
        subject = "Mathematics",
        gradeLevel = "Grade 4",
        language = "en",
        standardVersion = "ke-cbc-v1",
    )

    private class FakeProvider(
        override val name: String,
        override val costProfile: ProviderCostProfile,
        var failure: String? = null,
    ) : RoutedGenerationProvider {

        var generated = 0

        override fun generate(request: GenerationRequest): GenerationResult {
            generated += 1
            failure?.let { throw IllegalStateException(it) }
            return GenerationResult(provider = name, model = name + "-model", promptTokens = 10, completionTokens = 5)
        }

        override fun verifyAnswerKeys(request: AnswerVerificationRequest): AnswerVerificationResult {
            failure?.let { throw IllegalStateException(it) }
            return AnswerVerificationResult(provider = name, promptTokens = 4, completionTokens = 2)
        }
    }

    private fun cheap() = ProviderCostProfile(100, 100)

    private fun pricey() = ProviderCostProfile(900, 900)

    @Test
    fun `cheapest healthy provider wins the call and reports itself`() {
        val cheap = FakeProvider("cheap", cheap())
        val pricey = FakeProvider("pricey", pricey())
        val router = RoutingContentGenerationProvider(listOf(pricey, cheap), health(), RoutingPolicy.CHEAPEST)

        val result = router.generate(request())

        assertEquals("cheap", result.provider)
        assertEquals(1, cheap.generated)
        assertEquals(0, pricey.generated)
    }

    @Test
    fun `fastest observed provider wins under the FASTEST policy`() {
        val health = health()
        val slow = FakeProvider("slow", cheap())
        val fast = FakeProvider("fast", pricey())
        health.recordSuccess("slow", 900)
        health.recordSuccess("fast", 120)
        val router = RoutingContentGenerationProvider(listOf(slow, fast), health, RoutingPolicy.FASTEST)

        assertEquals("fast", router.generate(request()).provider)
    }

    @Test
    fun `a failing provider fails over to the next and the result names the server`() {
        val primary = FakeProvider("primary", cheap(), failure = "primary exploded")
        val secondary = FakeProvider("secondary", pricey())
        val router = RoutingContentGenerationProvider(listOf(primary, secondary), health(threshold = 1), RoutingPolicy.CHEAPEST)

        val result = router.generate(request())

        assertEquals("secondary", result.provider)
        assertTrue(primary.generated == 1 && secondary.generated == 1, "both providers must be tried")
    }

    @Test
    fun `every provider failing raises the ordered attempt list`() {
        val first = FakeProvider("first", cheap(), failure = "first down")
        val second = FakeProvider("second", pricey(), failure = "second down")
        val router = RoutingContentGenerationProvider(listOf(first, second), health(threshold = 1), RoutingPolicy.CHEAPEST)

        val failure = assertFailsWith<ProviderCallException> { router.generate(request()) }

        assertEquals(listOf("first", "second"), failure.attempts.map { it.provider })
        assertEquals("second", failure.lastProvider)
    }

    @Test
    fun `a demoted provider is moved behind a healthy one`() {
        val health = health(threshold = 1, cooldownSeconds = 120)
        val cheap = FakeProvider("cheap", cheap())
        val pricey = FakeProvider("pricey", pricey())
        health.recordFailure("cheap")
        assertTrue(health.isDemoted("cheap"))
        val router = RoutingContentGenerationProvider(listOf(cheap, pricey), health, RoutingPolicy.CHEAPEST)

        assertEquals("pricey", router.generate(request()).provider)
    }
}
