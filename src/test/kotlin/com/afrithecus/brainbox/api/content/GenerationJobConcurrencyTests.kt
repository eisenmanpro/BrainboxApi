package com.afrithecus.brainbox.api.content

import com.afrithecus.brainbox.api.content.ai.AnswerVerificationRequest
import com.afrithecus.brainbox.api.content.ai.AnswerVerificationResult
import com.afrithecus.brainbox.api.content.ai.ContentGenerationProvider
import com.afrithecus.brainbox.api.content.ai.GeneratedQuestion
import com.afrithecus.brainbox.api.content.ai.GeneratedStep
import com.afrithecus.brainbox.api.content.ai.GenerationRequest
import com.afrithecus.brainbox.api.content.ai.GenerationResult
import com.afrithecus.brainbox.api.content.ai.VerificationAnswer
import com.afrithecus.brainbox.api.content.entity.ConceptEntity
import com.afrithecus.brainbox.api.content.entity.CurriculumMapEntity
import com.afrithecus.brainbox.api.content.repository.ConceptRepository
import com.afrithecus.brainbox.api.content.repository.ContentUnitQuestionRepository
import com.afrithecus.brainbox.api.content.repository.ContentUnitRepository
import com.afrithecus.brainbox.api.content.repository.ContentUnitStepRepository
import com.afrithecus.brainbox.api.content.repository.CurriculumMapRepository
import com.afrithecus.brainbox.api.content.repository.GenerationJobRepository
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.model.Role
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.test.context.ActiveProfiles
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * H4: the generation worker drains one claimed batch through a bounded executor of
 * app.content.worker.concurrency threads. These tests run with concurrency 3 and
 * are deliberately NOT transactional: the executor runs jobs on other threads,
 * which cannot see an uncommitted test transaction. The class therefore owns an
 * isolated in-memory database (the shared test database is left untouched) and
 * parks any leftover QUEUED retry before each test.
 *
 * No assertion depends on wall-clock timing: poll() waits for the whole batch, so
 * every assertion runs after the batch has fully settled. Parallelism is proven by
 * a CyclicBarrier, not by elapsed time.
 */
@SpringBootTest(
    properties = [
        "app.content.run-mode=both",
        "app.content.worker.retry-backoff-seconds=0",
        "app.content.worker.concurrency=3",
        "app.content.worker.batch-size=6",
        // Silence the worker's one startup auto-poll; each test drives poll() itself.
        "app.content.worker.poll-interval-ms=3600000",
        // Own H2 database so these committed rows never leak into the shared
        // brainbox transactional tests.
        "spring.datasource.url=jdbc:h2:mem:brainbox-h4;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
    ],
)
@ActiveProfiles("test")
@Import(GenerationJobConcurrencyTests.H4ProviderConfig::class)
class GenerationJobConcurrencyTests(
    @Autowired private val worker: GenerationJobWorker,
    @Autowired private val jobService: GenerationJobService,
    @Autowired private val provider: ContentGenerationProvider,
    @Autowired private val generationJobs: GenerationJobRepository,
    @Autowired private val contentUnits: ContentUnitRepository,
    @Autowired private val unitSteps: ContentUnitStepRepository,
    @Autowired private val unitQuestions: ContentUnitQuestionRepository,
    @Autowired private val concepts: ConceptRepository,
    @Autowired private val curriculumMaps: CurriculumMapRepository,
    @Autowired private val users: UserRepository,
    @Autowired private val moderationPolicy: ModerationPolicyService,
) {

    private val fake: H4FakeProvider get() = provider as H4FakeProvider

    @BeforeEach
    fun setUp() {
        // A sibling test method may have left a QUEUED retry behind; park it so
        // this test's claim sees exactly its own batch. Jobs only ever live in
        // this class's own database, so nothing leaks to other test classes.
        generationJobs.findAll()
            .filter { it.status == "QUEUED" }
            .forEach {
                it.status = "SUCCEEDED"
                generationJobs.save(it)
            }
        ensureConceptAndMapping()
        ensureSystemAuthor()
        fake.reset()
        // Keep the worker's one startup auto-poll away from the seeding above;
        // runWorker clears this immediately before the explicit poll.
        moderationPolicy.setContentWorkerPaused(true)
    }

    /** Clears the seed-time pause and drives exactly one poll synchronously. */
    private fun runWorker() {
        moderationPolicy.setContentWorkerPaused(false)
        worker.poll()
    }

    @Test
    fun `concurrency 3 drains a batch of several jobs with the sequential outcomes`() {
        val keys = (1..6).map { "ke:cbc:grade4:mat-num-frac:lesson:h4-par-" + it }
        val jobs = keys.map { jobService.enqueue(request(it)) }

        // Force genuine overlap: each wave of three generate calls must meet at
        // the barrier. A sequential worker would time out instead of passing.
        fake.barrier = CyclicBarrier(3)
        runWorker()

        check(fake.maxActive.get() == 3) { "expected three jobs in flight, saw " + fake.maxActive.get() }
        check(fake.generationKeys.size == 6)
        val reloaded = jobs.map { generationJobs.findById(it.id).orElseThrow() }
        check(reloaded.all { it.status == "SUCCEEDED" }) { "every job must reach SUCCEEDED" }
        check(reloaded.all { it.attempts == 1 })
        keys.forEach { key ->
            val unit = requireNotNull(contentUnits.findByGenerationKey(key)) { "missing unit for " + key }
            check(unitSteps.findAllByUnitIdOrderByOrderIndexAsc(unit.id).size == 2)
            check(unitQuestions.findAllByUnitIdOrderByOrderIndexAsc(unit.id).size == 2)
        }
    }

    @Test
    fun `a failing job does not stop the other claimed jobs`() {
        val keys = (1..5).map { "ke:cbc:grade4:mat-num-frac:lesson:h4-fail-" + it }
        val jobs = keys.map { jobService.enqueue(request(it)) }

        // One-shot provider failure: exactly one job consumes it and is
        // rescheduled; the other four must still complete.
        fake.failNext = "model exploded"
        runWorker()

        check(fake.generationKeys.size == 5)
        val reloaded = jobs.map { generationJobs.findById(it.id).orElseThrow() }
        check(reloaded.count { it.status == "SUCCEEDED" } == 4) { "the other four jobs must complete" }
        val rescheduled = reloaded.single { it.status == "QUEUED" }
        check(rescheduled.attempts == 1)
        check(rescheduled.lastError!!.contains("model exploded"))
    }

    private fun request(key: String) = GenerationRequest(
        generationKey = key,
        taskType = "LESSON",
        taskTypeLabel = "Lesson",
        conceptCode = "MAT-FRAC-01",
        conceptName = "Fractions",
        subject = "Mathematics",
        gradeLevel = "Grade 4",
        language = "en",
        standardVersion = "v1",
        difficulty = 3,
        notes = "Keep it short.",
    )

    private fun ensureConceptAndMapping() {
        if (concepts.findByCode("MAT-FRAC-01") != null) return
        val concept = concepts.save(
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

    /**
     * Projection lazily creates one stable system author on its first call. Create
     * it up front so three concurrent projections never race on the same fixed id.
     */
    private fun ensureSystemAuthor() {
        if (users.existsById(SYSTEM_AUTHOR_ID)) return
        users.save(
            UserEntity().apply {
                id = SYSTEM_AUTHOR_ID
                name = "BrainBox Study Team"
                passwordHash = ""
                role = Role.ADMIN
                isActive = false
                isVerified = true
            }
        )
    }

    @TestConfiguration
    class H4ProviderConfig {
        @Bean
        @Primary
        fun contentGenerationProvider(): ContentGenerationProvider = H4FakeProvider()
    }

    /**
     * Thread-safe fake provider. It records the generation keys it saw, tracks the
     * peak number of concurrent generate calls, and offers a one-shot failNext and
     * an optional barrier. The one-shot failure is consumed under a lock, so
     * exactly one job fails even with several workers in flight.
     */
    class H4FakeProvider : ContentGenerationProvider {
        override val name: String = "h4-fake"

        val generationKeys = ConcurrentLinkedQueue<String>()
        private val active = AtomicInteger(0)
        val maxActive = AtomicInteger(0)

        @Volatile
        var failNext: String? = null

        /** When set, generate waits here until the configured number of calls overlap. */
        @Volatile
        var barrier: CyclicBarrier? = null

        fun reset() {
            generationKeys.clear()
            active.set(0)
            maxActive.set(0)
            failNext = null
            barrier = null
        }

        override fun generate(request: GenerationRequest): GenerationResult {
            generationKeys += request.generationKey
            val now = active.incrementAndGet()
            maxActive.updateAndGet { current -> maxOf(current, now) }
            try {
                barrier?.await(20, TimeUnit.SECONDS)
                val failure = synchronized(this) { failNext?.also { failNext = null } }
                if (failure != null) throw IllegalStateException(failure)
                return result(request)
            } finally {
                active.decrementAndGet()
            }
        }

        override fun verifyAnswerKeys(request: AnswerVerificationRequest): AnswerVerificationResult =
            AnswerVerificationResult(
                answers = request.questions.map { question ->
                    VerificationAnswer(
                        orderIndex = question.orderIndex,
                        answer = if (question.orderIndex == 0) "4" else "Two equal parts.",
                    )
                },
                model = "h4-fake-verifier",
                promptTokens = 200,
                completionTokens = 100,
            )

        private fun result(request: GenerationRequest) = GenerationResult(
            body = "Body for " + request.generationKey,
            steps = listOf(
                GeneratedStep(0, "Step one", "First step body", null),
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
            confidence = 0.95,
            model = "h4-fake-model",
            promptTokens = 1000,
            completionTokens = 500,
            sourceUrls = listOf("https://example.org/fractions"),
            license = "CC-BY-4.0",
        )
    }

    private companion object {
        /** Must match ContentProjectionService.SYSTEM_AUTHOR_ID. */
        val SYSTEM_AUTHOR_ID: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000A1")
    }
}
