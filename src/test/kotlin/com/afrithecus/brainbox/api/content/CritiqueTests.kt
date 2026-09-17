package com.afrithecus.brainbox.api.content

import com.afrithecus.brainbox.api.common.error.ApiException
import com.afrithecus.brainbox.api.content.ai.AnswerVerificationRequest
import com.afrithecus.brainbox.api.content.ai.AnswerVerificationResult
import com.afrithecus.brainbox.api.content.ai.ContentCritiqueRequest
import com.afrithecus.brainbox.api.content.ai.ContentGenerationProvider
import com.afrithecus.brainbox.api.content.ai.CritiqueFinding
import com.afrithecus.brainbox.api.content.ai.CritiqueResult
import com.afrithecus.brainbox.api.content.ai.GeneratedStep
import com.afrithecus.brainbox.api.content.ai.GenerationRequest
import com.afrithecus.brainbox.api.content.ai.GenerationResult
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.annotation.Transactional
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The Phase 7.5 LLM critic: a below-bar pedagogy score drives the supervisor loop
 * into a revision exactly like a validator blocker, and the stored critique is
 * reused on re-projection. A provider with no critic support fails closed.
 */
@SpringBootTest(
    properties = [
        "app.content.critique.enabled=true",
        "app.content.critique.min-score=0.8",
        "app.content.loop.max-iterations=2",
        "app.content.run-mode=api",
    ],
)
@ActiveProfiles("test")
@Transactional
@Import(CritiqueTests.ScriptedCriticConfig::class)
class CritiqueTests(
    @Autowired private val loop: ContentTaskLoop,
    @Autowired private val router: ContentRouter,
    @Autowired private val jobs: GenerationJobService,
    @Autowired private val provider: ContentGenerationProvider,
) {

    @TestConfiguration
    class ScriptedCriticConfig {
        @Bean
        @Primary
        fun contentGenerationProvider(): ContentGenerationProvider = ScriptedCritic()
    }

    class ScriptedCritic : ContentGenerationProvider {
        val generateCalls = AtomicInteger()
        val critiqueCalls = AtomicInteger()
        var lastNotes: String? = null

        override val name: String = "scripted-critic"

        override fun generate(request: GenerationRequest): GenerationResult {
            generateCalls.incrementAndGet()
            lastNotes = request.notes
            return GenerationResult(
                body = "Body for " + request.generationKey,
                steps = listOf(
                    GeneratedStep(0, "Step one", "First step body", null),
                    GeneratedStep(1, "Step two", "Second step body", null),
                    GeneratedStep(2, "Step three", "Third step body", null),
                ),
                questions = emptyList(),
                confidence = 0.95,
                model = name,
                provider = name,
                promptTokens = 10,
                completionTokens = 5,
                costMicros = 100,
            )
        }

        override fun verifyAnswerKeys(request: AnswerVerificationRequest): AnswerVerificationResult =
            AnswerVerificationResult(provider = name)

        override fun critique(request: ContentCritiqueRequest): CritiqueResult {
            val attempt = critiqueCalls.incrementAndGet()
            return if (attempt == 1) {
                CritiqueResult(
                    score = 0.5,
                    findings = listOf(CritiqueFinding("WARNING", "PEDAGOGY_UNCLEAR", "explain the first step")),
                    model = name,
                    provider = name,
                    promptTokens = 5,
                    completionTokens = 2,
                    costMicros = 50,
                )
            } else {
                CritiqueResult(
                    score = 0.9,
                    findings = emptyList(),
                    model = name,
                    provider = name,
                    promptTokens = 5,
                    completionTokens = 2,
                    costMicros = 50,
                )
            }
        }
    }

    @Test
    fun criticDrivesRevisionsUntilTheScoreClears() {
        val request = GenerationRequest(
            generationKey = "ke:cbc:grade4:mat:critique:" + UUID.randomUUID(),
            taskType = "NOTES",
            subject = "Mathematics",
            gradeLevel = "Grade 4",
            language = "en",
            standardVersion = "v1",
        )
        val job = jobs.enqueue(request, GenerationJobSource.USER)
        val unit = loop.run(job.id) ?: error("the loop must return the generated unit")
        val scripted = provider as ScriptedCritic

        assertEquals(2, scripted.generateCalls.get(), "the low critique must trigger one revision")
        assertEquals(2, scripted.critiqueCalls.get())
        assertEquals(0.9, unit.critiqueScore)
        assertTrue(
            scripted.lastNotes?.contains("pedagogy critic") == true,
            scripted.lastNotes.toString(),
        )

        // Idempotent: a re-projection reuses the stored critique and spends no call.
        assertEquals(0.9, router.critique(unit.id))
        assertEquals(2, scripted.critiqueCalls.get())
    }

    @Test
    fun aProviderWithoutCriticSupportFailsClosed() {
        val bare = object : ContentGenerationProvider {
            override val name: String = "bare"
            override fun generate(request: GenerationRequest): GenerationResult = GenerationResult()
            override fun verifyAnswerKeys(request: AnswerVerificationRequest): AnswerVerificationResult =
                AnswerVerificationResult()
        }
        assertFailsWith<ApiException> {
            bare.critique(ContentCritiqueRequest(taskType = "NOTES", subject = "Mathematics", gradeLevel = "Grade 4"))
        }
    }
}
