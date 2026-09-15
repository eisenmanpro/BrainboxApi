package com.afrithecus.brainbox.api.content

import com.afrithecus.brainbox.api.common.error.ApiErrorCode
import com.afrithecus.brainbox.api.common.error.ApiException
import com.afrithecus.brainbox.api.content.ai.AnswerVerificationRequest
import com.afrithecus.brainbox.api.content.ai.ContentGenerationProvider
import com.afrithecus.brainbox.api.content.ai.DisabledContentGenerationProvider
import com.afrithecus.brainbox.api.content.ai.GenerationRequest
import com.afrithecus.brainbox.api.content.entity.ConceptEntity
import com.afrithecus.brainbox.api.content.entity.ContentUnitEntity
import com.afrithecus.brainbox.api.content.entity.ContentUnitQuestionEntity
import com.afrithecus.brainbox.api.content.entity.ContentUnitStepEntity
import com.afrithecus.brainbox.api.content.entity.CurriculumMapEntity
import com.afrithecus.brainbox.api.content.repository.AgentRunRepository
import com.afrithecus.brainbox.api.content.repository.ConceptRepository
import com.afrithecus.brainbox.api.content.repository.ContentUnitQuestionRepository
import com.afrithecus.brainbox.api.content.repository.ContentUnitRepository
import com.afrithecus.brainbox.api.content.repository.ContentUnitStepRepository
import com.afrithecus.brainbox.api.content.repository.CurriculumMapRepository
import com.afrithecus.brainbox.api.content.repository.ModelCallRepository
import com.afrithecus.brainbox.api.learning.repository.LearningPostRepository
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.util.UUID
import kotlin.math.abs
import kotlin.test.assertFailsWith

/**
 * Phase 7.5f: independent answer-key verification. A second model interaction
 * solves each question without the stored key; only a full agreement lets an
 * assessment auto-approve. Reuses the ContentRouterTests fake provider so no
 * network call is made and the verification-call count is observable.
 *
 * The router is still the only provider caller and capture writer; these tests
 * drive it directly and then run projection so the auto-approval gate is real.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@Import(ContentRouterTests.FakeProviderConfig::class)
class AnswerVerificationTests(
    @Autowired private val router: ContentRouter,
    @Autowired private val provider: ContentGenerationProvider,
    @Autowired private val projection: ContentProjectionService,
    @Autowired private val contentUnits: ContentUnitRepository,
    @Autowired private val unitSteps: ContentUnitStepRepository,
    @Autowired private val unitQuestions: ContentUnitQuestionRepository,
    @Autowired private val concepts: ConceptRepository,
    @Autowired private val curriculumMaps: CurriculumMapRepository,
    @Autowired private val agentRuns: AgentRunRepository,
    @Autowired private val modelCalls: ModelCallRepository,
    @Autowired private val posts: LearningPostRepository,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val entityManager: EntityManager,
) {

    private val fake: ContentRouterTests.FakeContentGenerationProvider
        get() = provider as ContentRouterTests.FakeContentGenerationProvider

    private lateinit var concept: ConceptEntity

    @BeforeEach
    fun setUp() {
        fake.requests.clear()
        fake.failNext = null
        fake.clean = false
        fake.verificationRequests.clear()
        fake.verificationCalls = 0
        fake.verifyFailNext = null
        fake.verificationAnswers = null

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

    // ---------------------------------------------------------------- tests

    @Test
    fun `an assessment whose answers all agree verifies at one and auto-approves`() {
        val unit = seedAssessment("QUIZ", 8)
        fake.verificationAnswers = (0 until 8).associateWith { "A" }

        val agreement = router.verifyAnswerKeys(unit.id)

        check(agreement == 1.0) { "expected 1.0, got " + agreement }
        entityManager.flush()
        entityManager.clear()

        val verified = contentUnits.findById(unit.id).orElseThrow()
        check(verified.answerKeyVerifiedAt != null) { "verification timestamp must be stored" }
        check(verified.answerKeyAgreement != null && verified.answerKeyAgreement!! > 0.999)
        check(verified.answerKeyVerifiedModel == "fake-verifier")

        projection.project(unit.id)
        entityManager.flush()
        entityManager.clear()

        check(contentUnits.findById(unit.id).orElseThrow().reviewState == "REVIEWED")
        check(posts.findById(unit.id).orElseThrow().isPublished)
    }

    @Test
    fun `an assessment where one answer disagrees stays UNREVIEWED and hidden`() {
        val unit = seedAssessment("QUIZ", 8)
        fake.verificationAnswers = (0 until 8).associateWith { if (it == 3) "B" else "A" }

        val agreement = router.verifyAnswerKeys(unit.id)

        check(agreement != null && abs(agreement - 7.0 / 8.0) < 1e-9) {
            "expected 0.875, got " + agreement
        }

        projection.project(unit.id)
        entityManager.flush()
        entityManager.clear()

        val stored = contentUnits.findById(unit.id).orElseThrow()
        check(stored.answerKeyVerifiedAt != null)
        check(stored.reviewState == "UNREVIEWED") { "a disagreeing key must be an exception" }
        check(!posts.findById(unit.id).orElseThrow().isPublished)
    }

    @Test
    fun `an unverified assessment with questions does not auto-approve`() {
        val unit = seedAssessment("QUIZ", 8)
        check(fake.verificationCalls == 0)

        projection.project(unit.id)
        entityManager.flush()
        entityManager.clear()

        val stored = contentUnits.findById(unit.id).orElseThrow()
        check(stored.answerKeyVerifiedAt == null)
        check(stored.reviewState == "UNREVIEWED")
        check(!posts.findById(unit.id).orElseThrow().isPublished)
    }

    @Test
    fun `a non-assessment unit is unaffected by the answer-key gate`() {
        val unit = seedAssessment("NOTES", 8)

        projection.project(unit.id)
        entityManager.flush()
        entityManager.clear()

        check(contentUnits.findById(unit.id).orElseThrow().reviewState == "REVIEWED")
        check(posts.findById(unit.id).orElseThrow().isPublished)
    }

    @Test
    fun `verification is idempotent and reuses the stored agreement`() {
        val unit = seedAssessment("QUIZ", 4)
        fake.verificationAnswers = (0 until 4).associateWith { "A" }

        check(router.verifyAnswerKeys(unit.id) == 1.0)
        check(fake.verificationCalls == 1)

        entityManager.flush()
        entityManager.clear()
        check(router.verifyAnswerKeys(unit.id) == 1.0)
        check(fake.verificationCalls == 1) { "force=false must not spend another model call" }

        check(router.verifyAnswerKeys(unit.id, force = true) == 1.0)
        check(fake.verificationCalls == 2)
    }

    @Test
    fun `a failing verifier leaves the unit unverified and fails closed`() {
        val unit = seedAssessment("QUIZ", 8)
        fake.verifyFailNext = "verifier exploded"

        val error = assertFailsWith<ApiException> { router.verifyAnswerKeys(unit.id) }
        check(error.code == ApiErrorCode.SERVICE_UNAVAILABLE)
        check(error.message.contains("verifier exploded"))

        entityManager.flush()
        entityManager.clear()

        val stored = contentUnits.findById(unit.id).orElseThrow()
        check(stored.answerKeyVerifiedAt == null)
        check(stored.answerKeyAgreement == null)

        projection.project(unit.id)
        entityManager.flush()
        entityManager.clear()
        check(contentUnits.findById(unit.id).orElseThrow().reviewState == "UNREVIEWED")
        check(!posts.findById(unit.id).orElseThrow().isPublished)

        val verificationRun = agentRuns.findAllByGenerationKeyOrderByCreatedAtDesc(unit.generationKey)
            .single { it.promptVersion == "answer-verify-v1" }
        check(verificationRun.status == "FAILED")
        val calls = modelCalls.findAllByAgentRunId(verificationRun.id)
        check(calls.size == 1)
        check(!calls[0].success)
        check(calls[0].error!!.contains("verifier exploded"))
    }

    @Test
    fun `the verification call is captured as an agent run and a model call`() {
        val key = "test:verify:capture:" + UUID.randomUUID()
        val unit = router.resolve(request(key))
        check(router.verifyAnswerKeys(unit.id) == 1.0)

        entityManager.flush()
        entityManager.clear()

        val runs = agentRuns.findAllByGenerationKeyOrderByCreatedAtDesc(key)
        check(runs.size == 2) { "expected a generation run and a verification run, got " + runs.size }
        val verificationRun = runs.single { it.promptVersion == "answer-verify-v1" }
        check(verificationRun.status == "SUCCEEDED")
        check(verificationRun.model == "fake-verifier")

        val calls = modelCalls.findAllByAgentRunId(verificationRun.id)
        check(calls.size == 1)
        check(calls[0].provider == "fake")
        check(calls[0].model == "fake-verifier")
        check(calls[0].promptTokens == 200)
        check(calls[0].completionTokens == 100)
        check(calls[0].success)
        check(calls[0].costMicros > 0)
    }

    @Test
    fun `option letter and 1-based number answers are accepted for multiple choice`() {
        val unit = seedPlainUnit()
        seedQuestion(unit, 0, "MULTIPLE_CHOICE", "What is 2 + 2?", listOf("3", "4"), "4")
        seedQuestion(unit, 1, "MULTIPLE_CHOICE", "Capital of France?", listOf("Paris", "London"), "Paris")
        seedQuestion(unit, 2, "SHORT_ANSWER", "Name the capital of France.", null, "Paris")

        // 1-based option number, option letter and a case/punctuation variant.
        fake.verificationAnswers = mapOf(0 to "2", 1 to "A", 2 to "  paris. ")

        check(router.verifyAnswerKeys(unit.id) == 1.0)

        entityManager.flush()
        entityManager.clear()
        val stored = contentUnits.findById(unit.id).orElseThrow()
        check(stored.answerKeyAgreement == 1.0)
        check(stored.answerKeyVerifiedModel == "fake-verifier")
    }

    @Test
    fun `the disabled provider refuses verification`() {
        val error = assertFailsWith<ApiException> {
            DisabledContentGenerationProvider().verifyAnswerKeys(
                AnswerVerificationRequest(questions = emptyList())
            )
        }
        check(error.code == ApiErrorCode.SERVICE_UNAVAILABLE)
        check(error.message == "answer-key verification is disabled")
    }

    // ---------------------------------------------------------------- fixtures

    private fun request(key: String) = GenerationRequest(
        generationKey = key,
        taskType = "QUIZ",
        taskTypeLabel = "Quiz",
        conceptCode = concept.code,
        conceptName = concept.name,
        subject = "Mathematics",
        gradeLevel = "Grade 4",
        language = "en",
        standardVersion = "v1",
        difficulty = 3,
    )

    /**
     * A validator-clean unit: titled, three explained steps, one question attached
     * to the final step, a resolved concept and mapping, and a confidence above the
     * 0.90 critic floor. Every question is multiple choice with key "A".
     */
    private fun seedAssessment(taskType: String, questionCount: Int): ContentUnitEntity {
        val unit = seedPlainUnit().apply { this.taskType = taskType }
        contentUnits.save(unit)
        val steps = (0 until 3).map { index ->
            unitSteps.save(
                ContentUnitStepEntity().apply {
                    unitId = unit.id
                    orderIndex = index
                    title = "Step " + index
                    body = "Explanation for step " + index
                }
            )
        }
        repeat(questionCount) { index ->
            seedQuestion(
                unit = unit,
                orderIndex = index,
                qType = "MULTIPLE_CHOICE",
                text = "Question " + index + "?",
                options = listOf("A", "B", "C", "D"),
                correctAnswer = "A",
                stepId = if (index == questionCount - 1) steps.last().id else null,
            )
        }
        return unit
    }

    private fun seedPlainUnit(): ContentUnitEntity =
        contentUnits.save(
            ContentUnitEntity().apply {
                generationKey = "test:verify:" + UUID.randomUUID()
                taskType = "QUIZ"
                conceptId = concept.id
                subject = "Mathematics"
                gradeLevel = "Grade 4"
                language = "en"
                title = "Fractions check"
                body = "A short teaching body about fractions."
                confidence = 0.95
                reviewState = "UNREVIEWED"
                status = "DRAFT"
            }
        )

    private fun seedQuestion(
        unit: ContentUnitEntity,
        orderIndex: Int,
        qType: String,
        text: String,
        options: List<String>?,
        correctAnswer: String,
        stepId: UUID? = null,
    ) {
        unitQuestions.save(
            ContentUnitQuestionEntity().apply {
                unitId = unit.id
                this.stepId = stepId
                this.orderIndex = orderIndex
                this.qType = qType
                this.text = text
                this.options = options?.let { objectMapper.writeValueAsString(it) }
                this.correctAnswer = correctAnswer
                explanation = "Because."
                points = 2
                difficulty = 3
            }
        )
    }
}
