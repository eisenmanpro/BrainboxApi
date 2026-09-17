package com.afrithecus.brainbox.api.content

import com.afrithecus.brainbox.api.content.ai.AnswerVerificationRequest
import com.afrithecus.brainbox.api.content.ai.AnswerVerificationResult
import com.afrithecus.brainbox.api.content.ai.ContentGenerationProvider
import com.afrithecus.brainbox.api.content.ai.GeneratedStep
import com.afrithecus.brainbox.api.content.ai.GenerationRequest
import com.afrithecus.brainbox.api.content.ai.GenerationResult
import com.afrithecus.brainbox.api.content.repository.ContentUnitRepository
import com.afrithecus.brainbox.api.content.repository.ContentUnitStepRepository
import com.afrithecus.brainbox.api.content.repository.GenerationJobRepository
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
import kotlin.test.assertTrue

/**
 * The Phase 7.5 supervisor loop with max-iterations=2: the first attempt is a
 * structural BLOCKER, so the loop revises once with the findings folded into the
 * request and stores the second result. It also proves the loop state is persisted
 * on the job.
 */
@SpringBootTest(
    properties = [
        "app.content.loop.max-iterations=2",
        "app.content.run-mode=api",
    ],
)
@ActiveProfiles("test")
@Transactional
@Import(ContentTaskLoopTests.ScriptedProviderConfig::class)
class ContentTaskLoopTests(
    @Autowired private val loop: ContentTaskLoop,
    @Autowired private val jobs: GenerationJobService,
    @Autowired private val jobRepository: GenerationJobRepository,
    @Autowired private val units: ContentUnitRepository,
    @Autowired private val steps: ContentUnitStepRepository,
    @Autowired private val provider: ContentGenerationProvider,
) {

    @TestConfiguration
    class ScriptedProviderConfig {
        @Bean
        @Primary
        fun contentGenerationProvider(): ContentGenerationProvider = ScriptedProvider()
    }

    class ScriptedProvider : ContentGenerationProvider {
        val calls = AtomicInteger()

        override val name: String = "scripted"

        override fun generate(request: GenerationRequest): GenerationResult {
            val attempt = calls.incrementAndGet()
            return if (attempt == 1) {
                // No steps on a NOTES unit is a STRUCTURE_STEPS_TOO_FEW blocker.
                GenerationResult(
                    body = "Too thin",
                    steps = emptyList(),
                    questions = emptyList(),
                    confidence = 0.4,
                    model = "scripted-model",
                    provider = name,
                    promptTokens = 10,
                    completionTokens = 5,
                    costMicros = 100,
                )
            } else {
                GenerationResult(
                    body = "Revised body for " + request.generationKey,
                    steps = listOf(
                        GeneratedStep(0, "Step one", "First step body", null),
                        GeneratedStep(1, "Step two", "Second step body", null),
                        GeneratedStep(2, "Step three", "Third step body", null),
                    ),
                    questions = emptyList(),
                    confidence = 0.9,
                    model = "scripted-model",
                    provider = name,
                    promptTokens = 20,
                    completionTokens = 10,
                    costMicros = 200,
                )
            }
        }

        override fun verifyAnswerKeys(request: AnswerVerificationRequest): AnswerVerificationResult =
            AnswerVerificationResult(provider = name)
    }

    @Test
    fun revisesABlockedUnitOnceAndPersistsTheLoopState() {
        val request = GenerationRequest(
            generationKey = "ke:cbc:grade4:mat:loop:" + UUID.randomUUID(),
            taskType = "NOTES",
            subject = "Mathematics",
            gradeLevel = "Grade 4",
            language = "en",
            standardVersion = "v1",
        )
        val job = jobs.enqueue(request, GenerationJobSource.USER)

        val unit = loop.run(job.id) ?: error("the loop must return the generated unit")
        val scripted = provider as ScriptedProvider

        assertEquals(2, scripted.calls.get(), "a blocked unit must be revised exactly once at max-iterations=2")
        assertEquals(3, steps.findAllByUnitIdOrderByOrderIndexAsc(unit.id).size)
        assertTrue(unit.body == "Revised body for " + request.generationKey, unit.body.toString())

        val stored = jobRepository.findById(job.id).orElseThrow()
        assertEquals(2, stored.loopIterations)
        assertTrue(stored.loopCostMicros >= 300L, stored.loopCostMicros.toString())
        assertTrue(stored.loopFeedback?.contains("STRUCTURE_STEPS_TOO_FEW") == true, stored.loopFeedback.toString())
    }
}
