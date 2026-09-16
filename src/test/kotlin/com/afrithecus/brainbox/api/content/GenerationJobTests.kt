package com.afrithecus.brainbox.api.content

import com.afrithecus.brainbox.api.auth.web.AuthResponse
import com.afrithecus.brainbox.api.content.ai.ContentGenerationProvider
import com.afrithecus.brainbox.api.content.ai.GenerationRequest
import com.afrithecus.brainbox.api.content.entity.ConceptEntity
import com.afrithecus.brainbox.api.content.entity.ContentUnitEntity
import com.afrithecus.brainbox.api.content.entity.CurriculumMapEntity
import com.afrithecus.brainbox.api.content.repository.ConceptRepository
import com.afrithecus.brainbox.api.content.repository.ContentUnitQuestionRepository
import com.afrithecus.brainbox.api.content.repository.ContentUnitRepository
import com.afrithecus.brainbox.api.content.repository.ContentUnitStepRepository
import com.afrithecus.brainbox.api.content.repository.CurriculumMapRepository
import com.afrithecus.brainbox.api.content.repository.GenerationJobRepository
import com.afrithecus.brainbox.api.content.repository.ModerationOutcomeRepository
import com.afrithecus.brainbox.api.content.web.GenerateContentRequest
import com.afrithecus.brainbox.api.content.web.GenerationJobPayload
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.model.Role
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import com.afrithecus.brainbox.api.learning.LearningService
import com.afrithecus.brainbox.api.learning.repository.LearningPostRepository
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.util.UUID

/**
 * Phase 7.5a durable queue + worker split. Reuses the ContentRouterTests fake
 * provider so no network call is made and the call count is observable.
 */
@SpringBootTest(properties = ["app.content.run-mode=both", "app.content.worker.retry-backoff-seconds=0"])
@ActiveProfiles("test")
@Transactional
@Import(ContentRouterTests.FakeProviderConfig::class)
class GenerationJobWorkerTests(
    @Autowired private val worker: GenerationJobWorker,
    @Autowired private val router: ContentRouter,
    @Autowired private val jobService: GenerationJobService,
    @Autowired private val provider: ContentGenerationProvider,
    @Autowired private val generationJobs: GenerationJobRepository,
    @Autowired private val contentUnits: ContentUnitRepository,
    @Autowired private val unitSteps: ContentUnitStepRepository,
    @Autowired private val unitQuestions: ContentUnitQuestionRepository,
    @Autowired private val concepts: ConceptRepository,
    @Autowired private val curriculumMaps: CurriculumMapRepository,
    @Autowired private val outcomes: ModerationOutcomeRepository,
    @Autowired private val posts: LearningPostRepository,
    @Autowired private val learningService: LearningService,
    @Autowired private val users: UserRepository,
    @Autowired private val entityManager: EntityManager,
) {

    private val fake: ContentRouterTests.FakeContentGenerationProvider
        get() = provider as ContentRouterTests.FakeContentGenerationProvider

    @BeforeEach
    fun setUp() {
        fake.requests.clear()
        fake.failNext = null
        fake.clean = false
        fake.verificationRequests.clear()
        fake.verificationCalls = 0
        fake.verifyFailNext = null
        fake.verificationAnswers = null
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

    @Test
    fun `worker drains a queued job to succeeded and persists the unit`() {
        val key = "ke:cbc:grade4:mat-num-frac:lesson:worker-drain"
        val enqueued = jobService.enqueue(request(key))
        check(enqueued.status == "QUEUED")
        check(enqueued.attempts == 0)
        check(enqueued.requestPayload != null)

        worker.poll()

        entityManager.flush()
        entityManager.clear()

        val job = generationJobs.findById(enqueued.id).orElseThrow()
        check(job.status == "SUCCEEDED")
        check(job.attempts == 1)
        check(job.lastError == null)
        check(job.runId != null)
        check(fake.requests.size == 1)

        val unit = requireNotNull(contentUnits.findByGenerationKey(key))
        check(unit.body == "Body for " + key)
        check(unit.provenance == "GENERATED")
        check(unitSteps.findAllByUnitIdOrderByOrderIndexAsc(unit.id).size == 2)
        check(unitQuestions.findAllByUnitIdOrderByOrderIndexAsc(unit.id).size == 2)
    }

    @Test
    fun `enqueue is idempotent per generation key and reuses the newest row`() {
        val key = "ke:cbc:grade4:mat-num-frac:lesson:idempotent-enqueue"
        val first = jobService.enqueue(request(key))
        val second = jobService.enqueue(request(key))

        check(first.id == second.id)
        check(generationJobs.findAllByGenerationKeyOrderByCreatedAtAsc(key).size == 1)
    }

    @Test
    fun `runQueued is idempotent when the unit already exists`() {
        val key = "ke:cbc:grade4:mat-num-frac:lesson:idempotent-run"
        val existing = contentUnits.save(
            ContentUnitEntity().apply {
                generationKey = key
                taskType = "LESSON"
                subject = "Mathematics"
                gradeLevel = "Grade 4"
                language = "en"
                body = "Already generated"
            }
        )
        val job = jobService.enqueue(request(key))

        val result = router.runQueued(job.id)

        check(result?.id == existing.id)
        check(fake.requests.isEmpty()) { "the provider must not run for a cached unit" }
        check(generationJobs.findById(job.id).orElseThrow().status == "SUCCEEDED")
        check(contentUnits.findAll().size == 1)
    }

    @Test
    fun `provider failure retries with backoff until max attempts then fails`() {
        val key = "ke:cbc:grade4:mat-num-frac:lesson:retry"
        val job = jobService.enqueue(request(key))

        fake.failNext = "model exploded"
        worker.poll()

        val retrying = generationJobs.findById(job.id).orElseThrow()
        check(retrying.status == "QUEUED")
        check(retrying.attempts == 1)
        check(retrying.nextAttemptAt != null)
        check(retrying.lastError!!.contains("model exploded"))

        repeat(retrying.maxAttempts - 1) {
            fake.failNext = "model exploded"
            worker.poll()
        }

        val failed = generationJobs.findById(job.id).orElseThrow()
        check(failed.status == "FAILED")
        check(failed.attempts == failed.maxAttempts)
        check(failed.lastError!!.contains("model exploded"))
    }

    @Test
    fun `worker never throws when the provider fails`() {
        val key = "ke:cbc:grade4:mat-num-frac:lesson:no-throw"
        jobService.enqueue(request(key))
        fake.failNext = "model exploded"

        worker.poll()

        check(generationJobs.findAllByGenerationKeyOrderByCreatedAtAsc(key).single().status == "QUEUED")
    }

    @Test
    fun `concurrency 1 drains a multi-job batch and a failure does not stop it`() {
        val keys = (1..3).map { "ke:cbc:grade4:mat-num-frac:lesson:h4-seq-" + it }
        val jobs = keys.map { jobService.enqueue(request(it)) }

        // One-shot failure: exactly one job consumes it and is rescheduled, the
        // other two still complete, proving the per-job try/catch still isolates.
        fake.failNext = "model exploded"
        worker.poll()

        entityManager.flush()
        entityManager.clear()

        check(fake.requests.size == 3) { "the whole claimed batch must be attempted" }
        val reloaded = jobs.map { generationJobs.findById(it.id).orElseThrow() }
        check(reloaded.count { it.status == "SUCCEEDED" } == 2)
        val rescheduled = reloaded.single { it.status == "QUEUED" }
        check(rescheduled.attempts == 1)
        check(rescheduled.lastError!!.contains("model exploded"))
        check(reloaded.filter { it.status == "SUCCEEDED" }.all { it.attempts == 1 })
        check(keys.count { contentUnits.findByGenerationKey(it) != null } == 2)
    }

    @Test
    fun `clean generated unit auto-approves and becomes learner-visible through the worker`() {
        fake.clean = true
        val key = "ke:cbc:grade4:mat-num-frac:lesson:clean-auto-approve"
        jobService.enqueue(request(key))

        worker.poll()

        entityManager.flush()
        entityManager.clear()

        val unit = requireNotNull(contentUnits.findByGenerationKey(key))
        check(unit.reviewState == "REVIEWED") { "expected REVIEWED, got " + unit.reviewState }

        val outcome = outcomes.findByContentTypeAndContentIdAndContentVersion("UNIT", unit.id, 1)
        check(outcome != null)
        check(outcome!!.autoApproved)
        check(outcome.reviewerId == null)
        check(outcome.confidenceScore == 1.0)
        check(outcome.state == "REVIEWED")

        val post = posts.findById(unit.id).orElseThrow()
        check(post.isPublished)
        check(post.status == "PUBLISHED")
        check(post.title == "Fractions")

        val detail = learningService.detail(seedLearner(), unit.id.toString())
        check(detail.title == "Fractions")
        check(detail.isPublished)

        check(generationJobs.findAllByGenerationKeyOrderByCreatedAtAsc(key).single().status == "SUCCEEDED")
    }

    private fun seedLearner(): UserEntity {
        val suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10)
        return users.save(
            UserEntity().apply {
                phoneNumber = "07" + suffix.substring(0, 8)
                email = "worker-" + suffix + "@generation.test"
                passwordHash = "not-a-real-hash"
                name = "Worker Learner"
                role = Role.STUDENT
                isActive = true
                isVerified = true
            }
        )
    }
}

/**
 * API mode enqueues only; the same worker bean must not drain.
 */
@SpringBootTest(properties = ["app.content.run-mode=api"])
@ActiveProfiles("test")
@Transactional
class GenerationJobApiModeTests(
    @Autowired private val worker: GenerationJobWorker,
    @Autowired private val jobService: GenerationJobService,
    @Autowired private val generationJobs: GenerationJobRepository,
    @Autowired private val properties: AppContentProperties,
) {

    @Test
    fun `api run mode enqueues but does not drain`() {
        check(!properties.drainsJobs)
        val job = jobService.enqueue(
            GenerationRequest(
                generationKey = "ke:cbc:grade4:mat-num-frac:lesson:api-mode",
                taskType = "LESSON",
                subject = "Mathematics",
                gradeLevel = "Grade 4",
                language = "en",
                standardVersion = "v1",
            )
        )

        worker.poll()

        val stored = generationJobs.findById(job.id).orElseThrow()
        check(stored.status == "QUEUED")
        check(stored.attempts == 0)
        check(stored.nextAttemptAt == null)
    }
}

/**
 * Phase 7.5a submit/poll HTTP surface: teacher-only, enqueue then poll.
 */
@SpringBootTest(properties = ["app.content.run-mode=api"])
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class GenerationJobControllerTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val users: UserRepository,
    @Autowired private val passwordEncoder: PasswordEncoder,
    @Autowired private val generationJobs: GenerationJobRepository,
) {

    @Test
    fun `teacher submits and then polls the generation job`() {
        val teacher = seed("0744600301", Role.TEACHER, "Generation Teacher")
        val token = token(teacher)

        val submitted = read(
            mockMvc.perform(
                post("/teacher/content/generate")
                    .header("Authorization", auth(token))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(validRequest()))
            ).andExpect(status().isOk).andReturn().response.contentAsString,
            GenerationJobPayload::class.java,
        )
        check(submitted.status == "QUEUED")
        check(submitted.attempts == 0)
        check(submitted.maxAttempts == 5)
        check(submitted.unitId == null)
        check(submitted.runId == null)
        check(submitted.generationKey == "ke:cbc:grade4:mat-num-frac:lesson:http")

        // H2: the teacher submit path classifies its work as USER (interactive).
        val stored = generationJobs
            .findAllByGenerationKeyOrderByCreatedAtAsc("ke:cbc:grade4:mat-num-frac:lesson:http")
            .single()
        check(stored.source == "USER")

        val polled = read(
            mockMvc.perform(
                get("/teacher/content/jobs/" + submitted.id).header("Authorization", auth(token))
            ).andExpect(status().isOk).andReturn().response.contentAsString,
            GenerationJobPayload::class.java,
        )
        check(polled.id == submitted.id)
        check(polled.status == "QUEUED")
        check(polled.taskType == "LESSON")
        check(polled.createdAt > 0)
        check(polled.updatedAt > 0)
    }

    @Test
    fun `a blank required field is rejected as invalid argument`() {
        val teacher = seed("0744600302", Role.TEACHER, "Generation Teacher")
        mockMvc.perform(
            post("/teacher/content/generate")
                .header("Authorization", auth(token(teacher)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(validRequest().copy(generationKey = "  ")))
        ).andExpect(status().isBadRequest)
    }

    @Test
    fun `the generation surface requires a teacher token`() {
        mockMvc.perform(
            post("/teacher/content/generate")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(validRequest()))
        ).andExpect(status().isUnauthorized)

        val learner = seed("0744600303", Role.STUDENT, "Generation Learner")
        mockMvc.perform(
            get("/teacher/content/jobs/" + UUID.randomUUID())
                .header("Authorization", auth(token(learner)))
        ).andExpect(status().isForbidden)
    }

    // ---------------------------------------------------------------- fixtures

    private fun validRequest() = GenerateContentRequest(
        generationKey = "ke:cbc:grade4:mat-num-frac:lesson:http",
        taskType = "LESSON",
        subject = "Mathematics",
        gradeLevel = "Grade 4",
        language = "en",
        standardVersion = "v1",
        conceptCode = "MAT-FRAC-01",
        difficulty = 3,
        notes = "Keep it short.",
    )

    private fun seed(phone: String, role: Role, name: String): UserEntity =
        users.save(
            UserEntity().apply {
                phoneNumber = phone
                email = phone + "@generation.test"
                passwordHash = passwordEncoder.encode("password123") ?: error("encode")
                this.name = name
                this.role = role
                isActive = true
                isVerified = true
            }
        )

    private fun token(user: UserEntity): String {
        val body = mockMvc.perform(
            post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"identifier\":\"" + user.email + "\",\"password\":\"password123\"}")
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return objectMapper.readValue(body, AuthResponse::class.java).sessionToken!!
    }

    private fun auth(token: String) = "Bearer " + token

    private fun <T> read(body: String, type: Class<T>): T = objectMapper.readValue(body, type)
}
