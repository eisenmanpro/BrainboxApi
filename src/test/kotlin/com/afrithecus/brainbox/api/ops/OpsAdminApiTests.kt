package com.afrithecus.brainbox.api.ops

import com.afrithecus.brainbox.api.auth.web.AuthResponse
import com.afrithecus.brainbox.api.content.ContentPricing
import com.afrithecus.brainbox.api.content.entity.AgentRunEntity
import com.afrithecus.brainbox.api.content.entity.ConceptEntity
import com.afrithecus.brainbox.api.content.entity.ContentUnitEntity
import com.afrithecus.brainbox.api.content.entity.CurriculumMapEntity
import com.afrithecus.brainbox.api.content.entity.GenerationJobEntity
import com.afrithecus.brainbox.api.content.entity.ModelCallEntity
import com.afrithecus.brainbox.api.content.entity.ModerationOutcomeEntity
import com.afrithecus.brainbox.api.content.repository.AgentRunRepository
import com.afrithecus.brainbox.api.content.repository.ConceptRepository
import com.afrithecus.brainbox.api.content.repository.ContentUnitRepository
import com.afrithecus.brainbox.api.content.repository.CurriculumMapRepository
import com.afrithecus.brainbox.api.content.repository.GenerationJobRepository
import com.afrithecus.brainbox.api.content.repository.ModelCallRepository
import com.afrithecus.brainbox.api.content.repository.ModerationOutcomeRepository
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.model.Role
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import com.afrithecus.brainbox.api.ops.entity.OpsMetricRollupEntity
import com.afrithecus.brainbox.api.ops.repository.OpsMetricRollupRepository
import com.afrithecus.brainbox.api.ops.web.OpsCoveragePayload
import com.afrithecus.brainbox.api.ops.web.OpsSummary
import com.afrithecus.brainbox.api.ops.web.OpsTimeseriesPayload
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * O1 admin ops API over HTTP: /summary and /coverage shapes and numbers for a
 * seeded fixture, a bounded /timeseries, and ADMIN-only access (401/403).
 */
@SpringBootTest(properties = ["app.content.run-mode=api"])
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class OpsAdminApiTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val users: UserRepository,
    @Autowired private val passwordEncoder: PasswordEncoder,
    @Autowired private val concepts: ConceptRepository,
    @Autowired private val curriculumMaps: CurriculumMapRepository,
    @Autowired private val contentUnits: ContentUnitRepository,
    @Autowired private val outcomes: ModerationOutcomeRepository,
    @Autowired private val jobs: GenerationJobRepository,
    @Autowired private val modelCalls: ModelCallRepository,
    @Autowired private val agentRuns: AgentRunRepository,
    @Autowired private val rollups: OpsMetricRollupRepository,
    @Autowired private val clock: Clock,
) {

    @Test
    fun `the summary reports the seeded live numbers`() {
        val admin = seed("0744630001", Role.ADMIN, "Ops Admin")
        val subject = uniqueSubject()
        val concept = leafConcept(subject, "Grade 4")
        contentUnits.save(
            unit("Ops summary notes", subject, "Grade 4", "REVIEWED").apply { conceptId = concept.id }
        )
        outcomes.save(
            ModerationOutcomeEntity().apply {
                contentType = "UNIT"
                contentId = concept.id
                contentVersion = 1
                state = "REVIEWED"
                autoApproved = true
                decidedAt = clock.instant()
            }
        )
        contentUnits.save(unit("Ops exception notes", subject, "Grade 4", "UNREVIEWED"))
        jobs.save(job("FAILED", "BATCH"))
        jobs.save(job("QUEUED", "USER"))
        val provider = uniqueProvider()
        seedCall(provider, success = true, prompt = 1000, completion = 500, latencyMs = 100, error = null)
        seedCall(provider, success = false, prompt = 0, completion = 0, latencyMs = 300, error = "request timed out")

        val summary = summary(admin)

        check(summary.queueDepth["FAILED"]!! >= 1L)
        check(summary.queueDepth["QUEUED"]!! >= 1L)
        check(summary.oldestQueuedAgeSeconds != null) { "a queued job must expose its age" }
        check(summary.autoApprovedToday >= 1L)
        check(summary.exceptionUnitsToday >= 1L)
        check(summary.autoApprovalRate != null)
        check(summary.providerCallsToday >= 2L)
        check(summary.providerErrorsToday >= 1L)
        check((summary.providerErrorRate ?: 0.0) > 0.0)
        check(summary.averageGenerationLatencyMs != null)
        check(summary.promptTokensToday >= 1000L)
        check(summary.completionTokensToday >= 500L)
        check(summary.costMicrosToday >= ContentPricing.costMicros(1000, 500))
        check(summary.publishedUnitsToday >= 1L)
        check(summary.costPerPublishedItemMicros != null)
        check(summary.generationBudgetLimit == 500)
        check(summary.generationBudgetRemaining != null)

        val row = summary.coverageBySubjectGrade.single { it.subject == subject }
        check(row.totalTopics == 1L)
        check(row.coveredTopics == 1L)
        check(summary.coverage.totalTopics >= 1L)
    }

    @Test
    fun `the coverage endpoint reports per subject and grade counts`() {
        val admin = seed("0744630002", Role.ADMIN, "Ops Admin")
        val subject = uniqueSubject()
        val parent = concepts.save(
            ConceptEntity().apply {
                code = "OPS-P-" + UUID.randomUUID().toString().replace("-", "").take(6)
                name = "Ops parent"
                this.subject = subject
                sortOrder = 0
            }
        )
        val first = childConcept(parent, subject)
        val second = childConcept(parent, subject)
        contentUnits.save(
            unit("Covered notes", subject, "Grade 9", "REVIEWED").apply { conceptId = first.id }
        )

        val coverage = coverage(admin)
        val row = coverage.rows.single { it.subject == subject }
        check(row.grade == "Grade 9")
        check(row.totalTopics == 2L) { "expected two leaf topics, got " + row.totalTopics }
        check(row.coveredTopics == 1L) { "only the first topic has a published unit" }
        check(row.practicePapersPublished == 0L)
        check(row.studyGuidesPublished == 0L)
        check(second.id != first.id)
    }

    @Test
    fun `the timeseries returns ordered points and is bounded`() {
        val admin = seed("0744630003", Role.ADMIN, "Ops Admin")
        val base = clock.instant().truncatedTo(ChronoUnit.HOURS).minus(10_000, ChronoUnit.HOURS)
        val metric = "test.series." + UUID.randomUUID().toString().replace("-", "").take(6)

        // Three explicit points, returned in order.
        rollups.save(rollupPoint(base, metric, "d", 1.0))
        rollups.save(rollupPoint(base.plus(1, ChronoUnit.HOURS), metric, "d", 2.0))
        rollups.save(rollupPoint(base.plus(2, ChronoUnit.HOURS), metric, "d", 3.0))
        val three = timeseries(admin, metric, "d", base.toEpochMilli(), base.plus(3, ChronoUnit.HOURS).toEpochMilli())
        check(three.points.size == 3)
        check(three.points.map { it.value } == listOf(1.0, 2.0, 3.0))
        check(three.points.map { it.bucketStart } == three.points.map { it.bucketStart }.sorted())
        check(!three.truncated)
        check(three.limit == 1_000)

        // Over the cap: 1001 points in, 1000 returned and truncated.
        val wide = "test.series.wide." + UUID.randomUUID().toString().replace("-", "").take(6)
        rollups.saveAll(
            (0..1_000).map { index -> rollupPoint(base.plus(index.toLong(), ChronoUnit.HOURS), wide, "d", index.toDouble()) }
        )
        val bounded = timeseries(admin, wide, "d", base.toEpochMilli(), base.plus(1_001, ChronoUnit.HOURS).toEpochMilli())
        check(bounded.truncated) { "a series over the cap must report truncation" }
        check(bounded.points.size == bounded.limit)
    }

    @Test
    fun `the ops routes require an admin token`() {
        mockMvc.perform(get("/admin/ops/summary")).andExpect(status().isUnauthorized)
        mockMvc.perform(get("/admin/ops/coverage")).andExpect(status().isUnauthorized)
        mockMvc.perform(get("/admin/ops/timeseries").param("metric", "content.units.published"))
            .andExpect(status().isUnauthorized)
        mockMvc.perform(get("/admin/ops/audit")).andExpect(status().isUnauthorized)

        val teacher = seed("0744630004", Role.TEACHER, "Ops Teacher")
        mockMvc.perform(get("/admin/ops/summary").header("Authorization", auth(token(teacher))))
            .andExpect(status().isForbidden)
        mockMvc.perform(get("/admin/ops/coverage").header("Authorization", auth(token(teacher))))
            .andExpect(status().isForbidden)
        mockMvc.perform(
            get("/admin/ops/timeseries").param("metric", "content.units.published")
                .header("Authorization", auth(token(teacher)))
        ).andExpect(status().isForbidden)
        mockMvc.perform(get("/admin/ops/audit").header("Authorization", auth(token(teacher))))
            .andExpect(status().isForbidden)
    }

    @Test
    fun `the timeseries rejects a missing metric and an empty window`() {
        val admin = seed("0744630005", Role.ADMIN, "Ops Admin")
        mockMvc.perform(
            get("/admin/ops/timeseries").header("Authorization", auth(token(admin)))
        ).andExpect(status().isBadRequest)
        mockMvc.perform(
            get("/admin/ops/timeseries")
                .param("metric", "content.units.published")
                .param("from", "2000")
                .param("to", "1000")
                .header("Authorization", auth(token(admin)))
        ).andExpect(status().isBadRequest)
    }

    // ---------------------------------------------------------------- fixtures

    private fun summary(admin: UserEntity): OpsSummary = read(
        mockMvc.perform(get("/admin/ops/summary").header("Authorization", auth(token(admin))))
            .andExpect(status().isOk).andReturn().response.contentAsString,
        OpsSummary::class.java,
    )

    private fun coverage(admin: UserEntity): OpsCoveragePayload = read(
        mockMvc.perform(get("/admin/ops/coverage").header("Authorization", auth(token(admin))))
            .andExpect(status().isOk).andReturn().response.contentAsString,
        OpsCoveragePayload::class.java,
    )

    private fun timeseries(admin: UserEntity, metric: String, dimension: String, from: Long, to: Long): OpsTimeseriesPayload = read(
        mockMvc.perform(
            get("/admin/ops/timeseries")
                .param("metric", metric)
                .param("dimension", dimension)
                .param("from", from.toString())
                .param("to", to.toString())
                .header("Authorization", auth(token(admin)))
        ).andExpect(status().isOk).andReturn().response.contentAsString,
        OpsTimeseriesPayload::class.java,
    )

    private fun uniqueSubject(): String = "Ops Subject " + UUID.randomUUID().toString().replace("-", "").take(6)

    private fun uniqueProvider(): String = "test-provider-" + UUID.randomUUID().toString().replace("-", "").take(8)

    private fun leafConcept(subject: String, grade: String): ConceptEntity {
        val parent = concepts.save(
            ConceptEntity().apply {
                code = "OPS-STR-" + UUID.randomUUID().toString().replace("-", "").take(6)
                name = "Ops strand"
                this.subject = subject
                sortOrder = 0
            }
        )
        return childConcept(parent, subject, grade)
    }

    private fun childConcept(parent: ConceptEntity, subject: String, grade: String = "Grade 9"): ConceptEntity {
        val child = concepts.save(
            ConceptEntity().apply {
                code = "OPS-TOP-" + UUID.randomUUID().toString().replace("-", "").take(6)
                name = "Ops topic"
                this.subject = subject
                parentId = parent.id
                sortOrder = 0
            }
        )
        curriculumMaps.save(
            CurriculumMapEntity().apply {
                conceptId = child.id
                countryCode = "KE"
                curriculum = "CBC"
                gradeLevel = grade
                strandName = "Ops strand"
                sortOrder = 0
            }
        )
        return child
    }

    private fun unit(title: String, subject: String, grade: String, state: String): ContentUnitEntity =
        ContentUnitEntity().apply {
            generationKey = "test:ops:" + UUID.randomUUID()
            taskType = "NOTES"
            this.title = title
            this.subject = subject
            gradeLevel = grade
            language = "en"
            body = "Ops body"
            provenance = "GENERATED"
            reviewState = state
        }

    private fun job(status: String, source: String): GenerationJobEntity =
        GenerationJobEntity().apply {
            generationKey = "test:ops:job:" + UUID.randomUUID()
            taskType = "NOTES"
            gradeLevel = "Grade 4"
            this.status = status
            this.source = source
        }

    private fun seedCall(
        provider: String,
        success: Boolean,
        prompt: Int,
        completion: Int,
        latencyMs: Long,
        error: String?,
    ) {
        val run = agentRuns.save(
            AgentRunEntity().apply {
                generationKey = "test:ops:run:" + UUID.randomUUID()
                status = "SUCCEEDED"
            }
        )
        modelCalls.save(
            ModelCallEntity().apply {
                agentRunId = run.id
                this.provider = provider
                promptTokens = prompt
                completionTokens = completion
                costMicros = if (success) ContentPricing.costMicros(prompt, completion) else 0L
                this.latencyMs = latencyMs
                this.success = success
                this.error = error
            }
        )
    }

    private fun rollupPoint(bucket: java.time.Instant, metric: String, dimension: String, value: Double) =
        OpsMetricRollupEntity().apply {
            bucketStart = bucket
            this.metric = metric
            this.dimension = dimension
            this.value = value
        }

    private fun seed(phone: String, role: Role, name: String): UserEntity =
        users.save(
            UserEntity().apply {
                phoneNumber = phone
                email = phone + "@ops.test"
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
