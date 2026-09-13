package com.afrithecus.brainbox.api.content

import com.afrithecus.brainbox.api.common.error.ApiErrorCode
import com.afrithecus.brainbox.api.common.error.ApiException
import com.afrithecus.brainbox.api.content.ai.ContentGenerationProvider
import com.afrithecus.brainbox.api.content.ai.DisabledContentGenerationProvider
import com.afrithecus.brainbox.api.content.ai.GeneratedQuestion
import com.afrithecus.brainbox.api.content.ai.GeneratedStep
import com.afrithecus.brainbox.api.content.ai.GenerationRequest
import com.afrithecus.brainbox.api.content.ai.GenerationResult
import com.afrithecus.brainbox.api.content.entity.ConceptEntity
import com.afrithecus.brainbox.api.content.entity.CurriculumMapEntity
import com.afrithecus.brainbox.api.content.mcp.McpToolClient
import com.afrithecus.brainbox.api.content.repository.AgentRunRepository
import com.afrithecus.brainbox.api.content.repository.ConceptRepository
import com.afrithecus.brainbox.api.content.repository.ContentUnitQuestionRepository
import com.afrithecus.brainbox.api.content.repository.ContentUnitRepository
import com.afrithecus.brainbox.api.content.repository.ContentUnitStepRepository
import com.afrithecus.brainbox.api.content.repository.CurriculumMapRepository
import com.afrithecus.brainbox.api.content.repository.GenerationJobRepository
import com.afrithecus.brainbox.api.content.repository.ModelCallRepository
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import kotlin.test.assertFailsWith

/**
 * Phase 7.2: the cache-first router (miss, cache hit, capture rows), the MCP tool
 * seam and the disabled-provider truth check. A fake provider is supplied as the
 * @Primary bean so no network call is made and the call count is observable.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@Import(ContentRouterTests.FakeProviderConfig::class)
class ContentRouterTests(
    @Autowired private val router: ContentRouter,
    @Autowired private val provider: ContentGenerationProvider,
    @Autowired private val contentUnits: ContentUnitRepository,
    @Autowired private val unitSteps: ContentUnitStepRepository,
    @Autowired private val unitQuestions: ContentUnitQuestionRepository,
    @Autowired private val generationJobs: GenerationJobRepository,
    @Autowired private val agentRuns: AgentRunRepository,
    @Autowired private val modelCalls: ModelCallRepository,
    @Autowired private val concepts: ConceptRepository,
    @Autowired private val curriculumMaps: CurriculumMapRepository,
    @Autowired private val mcpToolClient: McpToolClient,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val entityManager: EntityManager,
) {

    @TestConfiguration
    class FakeProviderConfig {
        @Bean
        @Primary
        fun contentGenerationProvider(): ContentGenerationProvider = FakeContentGenerationProvider()
    }

    class FakeContentGenerationProvider : ContentGenerationProvider {
        override val name: String = "fake"
        val requests = mutableListOf<GenerationRequest>()
        var failNext: String? = null

        override fun generate(request: GenerationRequest): GenerationResult {
            requests += request
            failNext?.let { message ->
                failNext = null
                throw IllegalStateException(message)
            }
            return GenerationResult(
                body = "Body for " + request.generationKey,
                steps = listOf(
                    GeneratedStep(0, "Step one", "First step body", "<svg xmlns=\"http://www.w3.org/2000/svg\"/>"),
                    GeneratedStep(1, "Step two", "Second step body", null),
                ),
                questions = listOf(
                    GeneratedQuestion(
                        orderIndex = 0,
                        stepIndex = 0,
                        type = "MULTIPLE_CHOICE",
                        text = "What is 2 + 2?",
                        options = listOf("3", "4"),
                        correctAnswer = "4",
                        explanation = "Two plus two is four.",
                        points = 2,
                        difficulty = 2,
                    ),
                    GeneratedQuestion(
                        orderIndex = 1,
                        stepIndex = null,
                        type = "SHORT_ANSWER",
                        text = "Explain halves.",
                        correctAnswer = "Two equal parts.",
                        points = 3,
                        difficulty = 4,
                    ),
                ),
                confidence = 0.91,
                model = "fake-model",
                promptTokens = 1000,
                completionTokens = 500,
                sourceUrls = listOf("https://example.org/fractions"),
                license = "CC-BY-4.0",
            )
        }
    }

    private val fake: FakeContentGenerationProvider
        get() = provider as FakeContentGenerationProvider

    private lateinit var concept: ConceptEntity

    @BeforeEach
    fun setUp() {
        fake.requests.clear()
        fake.failNext = null
        concept = concepts.save(
            ConceptEntity().apply {
                code = "MAT-FRAC-01"
                name = "Fractions"
                description = "Compare, order and compute with fractions."
                subject = "Mathematics"
                sortOrder = 1
            }
        )
        curriculumMaps.save(
            CurriculumMapEntity().apply {
                conceptId = concept.id
                countryCode = "KE"
                curriculum = "CBC"
                gradeLevel = "Grade 4"
                strandCode = "MAT-NUM-FRAC"
                strandName = "Fractions"
                learningOutcome = "Compare fractions with unlike denominators."
                sortOrder = 1
            }
        )
    }

    private fun request(generationKey: String) = GenerationRequest(
        generationKey = generationKey,
        taskType = "LESSON",
        taskTypeLabel = "Lesson",
        conceptCode = concept.code,
        conceptName = concept.name,
        subject = "Mathematics",
        gradeLevel = "Grade 4",
        language = "en",
        standardVersion = "v1",
        difficulty = 3,
        notes = "Keep it short.",
    )

    @Test
    fun `miss generates and persists the unit with steps and questions`() {
        val key = "ke:cbc:grade4:mat-num-frac:lesson:miss"
        router.resolve(request(key))

        entityManager.flush()
        entityManager.clear()

        val stored = requireNotNull(contentUnits.findByGenerationKey(key))
        check(stored.subject == "Mathematics")
        check(stored.conceptId == concept.id)
        check(stored.gradeLevel == "Grade 4")
        check(stored.language == "en")
        check(stored.standardVersion == "v1")
        check(stored.provenance == "GENERATED")
        check(stored.body == "Body for " + key)
        check(stored.model == "fake-model")
        check(stored.tokens == 1500)
        check(stored.license == "CC-BY-4.0")
        check(stored.sourceUrls == "https://example.org/fractions")
        check(stored.confidence != null && kotlin.math.abs(stored.confidence!! - 0.91) < 1e-9)
        check(stored.status == "DRAFT")
        check(stored.reviewState == "UNREVIEWED")

        val steps = unitSteps.findAllByUnitIdOrderByOrderIndexAsc(stored.id)
        check(steps.size == 2)
        check(steps[0].orderIndex == 0)
        check(steps[0].title == "Step one")
        check(steps[0].body == "First step body")
        check(steps[0].figureSvg != null)
        check(steps[1].orderIndex == 1)
        check(steps[1].figureSvg == null)

        val questions = unitQuestions.findAllByUnitIdOrderByOrderIndexAsc(stored.id)
        check(questions.size == 2)
        check(questions[0].qType == "MULTIPLE_CHOICE")
        check(questions[0].text == "What is 2 + 2?")
        check(questions[0].options == "[\"3\",\"4\"]")
        check(questions[0].correctAnswer == "4")
        check(questions[0].points == 2)
        check(questions[0].difficulty == 2)
        check(questions[0].stepId == steps[0].id)
        check(questions[1].stepId == null)
        check(questions[1].qType == "SHORT_ANSWER")
    }

    @Test
    fun `second resolve with the same key is a cache hit and the provider is called once`() {
        val key = "ke:cbc:grade4:mat-num-frac:lesson:hit"
        val first = router.resolve(request(key))
        val second = router.resolve(request(key))

        check(first.id == second.id)
        check(fake.requests.size == 1)
        entityManager.flush()
        entityManager.clear()
        check(contentUnits.findByGenerationKey(key)?.id == first.id)
    }

    @Test
    fun `agent run and model call capture the provider call`() {
        val key = "ke:cbc:grade4:mat-num-frac:lesson:capture"
        router.resolve(request(key))

        entityManager.flush()
        entityManager.clear()

        val runs = agentRuns.findAllByGenerationKeyOrderByCreatedAtDesc(key)
        check(runs.size == 1)
        val run = runs[0]
        check(run.status == "SUCCEEDED")
        check(run.jobId != null)
        check(run.model == "fake-model")
        check(run.confidence != null)

        val calls = modelCalls.findAllByAgentRunId(run.id)
        check(calls.size == 1)
        check(calls[0].provider == "fake")
        check(calls[0].model == "fake-model")
        check(calls[0].promptTokens == 1000)
        check(calls[0].completionTokens == 500)
        check(calls[0].success)
        check(calls[0].costMicros > 0)
        check(calls[0].latencyMs >= 0)
        check(calls[0].createdAt.toEpochMilli() > 0)

        val job = generationJobs.findAllByGenerationKeyOrderByCreatedAtAsc(key).single()
        check(job.status == "SUCCEEDED")
        check(job.runId == run.id)
        check(job.attempts == 1)
        check(job.lastError == null)
    }

    @Test
    fun `provider failure marks the job FAILED and surfaces an ApiException`() {
        val key = "ke:cbc:grade4:mat-num-frac:lesson:fail"
        fake.failNext = "model exploded"

        val error = assertFailsWith<ApiException> { router.resolve(request(key)) }
        check(error.code == ApiErrorCode.SERVICE_UNAVAILABLE)
        check(error.message.contains("model exploded"))

        entityManager.flush()
        entityManager.clear()

        check(contentUnits.findByGenerationKey(key) == null)
        val job = generationJobs.findAllByGenerationKeyOrderByCreatedAtAsc(key).single()
        check(job.status == "FAILED")
        check(job.lastError!!.contains("model exploded"))
        val run = agentRuns.findAllByGenerationKeyOrderByCreatedAtDesc(key).single()
        check(run.status == "FAILED")
        val calls = modelCalls.findAllByAgentRunId(run.id)
        check(calls.size == 1)
        check(!calls[0].success)
        check(calls[0].error!!.contains("model exploded"))
    }

    @Test
    fun `mcp tool client resolves concept lookup by code and by curriculum`() {
        check(mcpToolClient.names().contains("concept_lookup"))

        val byCode = objectMapper.createObjectNode().put("code", concept.code)
        val direct = mcpToolClient.invoke("concept_lookup", byCode)
        check(direct.get("code")?.asString() == concept.code)
        check(direct.get("name")?.asString() == "Fractions")

        val byCurriculum = objectMapper.createObjectNode()
        byCurriculum.put("countryCode", "KE")
        byCurriculum.put("curriculum", "CBC")
        byCurriculum.put("strandCode", "MAT-NUM-FRAC")
        val matches = mcpToolClient.invoke("concept_lookup", byCurriculum)
        check(matches.isArray)
        check(matches.size() == 1)
        check(matches.get(0).get("code")?.asString() == concept.code)
        check(matches.get(0).get("curriculum")?.asString() == "CBC")

        val unknown = assertFailsWith<ApiException> {
            mcpToolClient.invoke("does_not_exist", objectMapper.createObjectNode())
        }
        check(unknown.code == ApiErrorCode.NOT_FOUND)
    }

    @Test
    fun `disabled provider throws instead of faking content when app ai is off`() {
        val error = assertFailsWith<ApiException> {
            DisabledContentGenerationProvider().generate(request("ke:cbc:grade4:mat-num-frac:lesson:disabled"))
        }
        check(error.code == ApiErrorCode.SERVICE_UNAVAILABLE)
        check(error.message == "content generation is disabled")
    }
}
