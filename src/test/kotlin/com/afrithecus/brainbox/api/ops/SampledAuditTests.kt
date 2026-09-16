package com.afrithecus.brainbox.api.ops

import com.afrithecus.brainbox.api.auth.web.AuthResponse
import com.afrithecus.brainbox.api.common.error.ApiErrorCode
import com.afrithecus.brainbox.api.common.error.ApiException
import com.afrithecus.brainbox.api.content.AuditSampling
import com.afrithecus.brainbox.api.content.ContentProjectionService
import com.afrithecus.brainbox.api.content.ModerationPolicyService
import com.afrithecus.brainbox.api.content.ReviewService
import com.afrithecus.brainbox.api.content.entity.ConceptEntity
import com.afrithecus.brainbox.api.content.entity.ContentUnitEntity
import com.afrithecus.brainbox.api.content.entity.ContentUnitQuestionEntity
import com.afrithecus.brainbox.api.content.entity.ContentUnitStepEntity
import com.afrithecus.brainbox.api.content.entity.CurriculumMapEntity
import com.afrithecus.brainbox.api.content.repository.ConceptRepository
import com.afrithecus.brainbox.api.content.repository.ContentUnitQuestionRepository
import com.afrithecus.brainbox.api.content.repository.ContentUnitRepository
import com.afrithecus.brainbox.api.content.repository.ContentUnitStepRepository
import com.afrithecus.brainbox.api.content.repository.CurriculumMapRepository
import com.afrithecus.brainbox.api.content.repository.ModerationOutcomeRepository
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.model.Role
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import com.afrithecus.brainbox.api.learning.LearningService
import com.afrithecus.brainbox.api.learning.repository.LearningPostRepository
import com.afrithecus.brainbox.api.ops.web.OpsAuditPayload
import jakarta.persistence.EntityManager
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
import java.util.UUID
import kotlin.test.assertFailsWith

/**
 * O1 sampled audit of machine approvals. The sample flag is a stable hash of the
 * content id (reproducible, never random per call); the sampled units appear in
 * GET /admin/ops/audit; and a human REJECT of an already-published auto-approval
 * actually unpublishes it from the learner read.
 */
@SpringBootTest(properties = ["app.content.run-mode=api"])
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class SampledAuditTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val users: UserRepository,
    @Autowired private val passwordEncoder: PasswordEncoder,
    @Autowired private val policy: ModerationPolicyService,
    @Autowired private val projection: ContentProjectionService,
    @Autowired private val reviewService: ReviewService,
    @Autowired private val learningService: LearningService,
    @Autowired private val concepts: ConceptRepository,
    @Autowired private val curriculumMaps: CurriculumMapRepository,
    @Autowired private val contentUnits: ContentUnitRepository,
    @Autowired private val steps: ContentUnitStepRepository,
    @Autowired private val questions: ContentUnitQuestionRepository,
    @Autowired private val outcomes: ModerationOutcomeRepository,
    @Autowired private val posts: LearningPostRepository,
    @Autowired private val entityManager: EntityManager,
) {

    @Test
    fun `the sample decision is a stable content-id hash`() {
        val ids = (1..200L).map { UUID(it, it * 31L) }
        val sampled = ids.filter { AuditSampling.isSampled(it, 2.0) }
        check(sampled.size in 1 until ids.size) { "2 percent should sample some but not all ids" }
        check(ids.filter { AuditSampling.isSampled(it, 2.0) } == sampled) { "the sample must be reproducible" }
        check(ids.none { AuditSampling.isSampled(it, 0.0) }) { "0 percent samples nothing" }
        check(ids.all { AuditSampling.isSampled(it, 100.0) }) { "100 percent samples everything" }
    }

    @Test
    fun `at 100 percent every auto-approval is flagged and listed but at 0 none are`() {
        val admin = seed("0744640001", Role.ADMIN, "Audit Admin")
        policy.setAutoApproveAuditSamplePercent(100.0)
        val sampledUnit = seedCleanUnit("Audit sampled")
        projection.project(sampledUnit.id)
        flushAndClear()
        check(requireNotNull(outcomes.findByContentTypeAndContentIdAndContentVersion("UNIT", sampledUnit.id, 1)).auditSample)

        policy.setAutoApproveAuditSamplePercent(0.0)
        val unsampledUnit = seedCleanUnit("Audit unsampled")
        projection.project(unsampledUnit.id)
        flushAndClear()
        check(!requireNotNull(outcomes.findByContentTypeAndContentIdAndContentVersion("UNIT", unsampledUnit.id, 1)).auditSample)

        val audit = audit(admin)
        check(audit.items.any { it.contentId == sampledUnit.id.toString() }) { "the sampled unit must be listed" }
        check(audit.items.none { it.contentId == unsampledUnit.id.toString() }) { "the unsampled unit must not be listed" }
    }

    @Test
    fun `rejecting a sampled auto-approved unit unpublishes it`() {
        val teacher = seed("0744640002", Role.TEACHER, "Audit Teacher")
        val learner = seed("0744640003", Role.STUDENT, "Audit Learner")
        policy.setAutoApproveAuditSamplePercent(100.0)

        val unit = seedCleanUnit("Audit reject")
        projection.project(unit.id)
        flushAndClear()
        check(contentUnits.findById(unit.id).orElseThrow().reviewState == "REVIEWED")
        check(posts.findById(unit.id).orElseThrow().isPublished)
        check(learningService.detail(learner, unit.id.toString()).isPublished)

        reviewService.recordDecision(teacher, "UNIT", unit.id, 1, "REJECT", listOf("wrong_answer"), "bad key")
        flushAndClear()

        check(contentUnits.findById(unit.id).orElseThrow().reviewState == "REJECTED")
        check(!posts.findById(unit.id).orElseThrow().isPublished) { "a rejected unit must be re-projected hidden" }
        val error = assertFailsWith<ApiException> { learningService.detail(learner, unit.id.toString()) }
        check(error.code == ApiErrorCode.NOT_FOUND) { "the learner read must no longer return the rejected unit" }
    }

    // ---------------------------------------------------------------- fixtures

    private fun audit(admin: UserEntity): OpsAuditPayload = objectMapper.readValue(
        mockMvc.perform(get("/admin/ops/audit").header("Authorization", auth(token(admin))))
            .andExpect(status().isOk).andReturn().response.contentAsString,
        OpsAuditPayload::class.java,
    )

    /**
     * A NOTES unit with every validator satisfied: a title, three explained steps,
     * a multiple-choice question attached to the final step, a resolvable concept
     * with a matching curriculum mapping, a recognised language and confidence 1.0.
     * The auto-approval gates therefore all pass.
     */
    private fun seedCleanUnit(title: String): ContentUnitEntity {
        val concept = concepts.save(
            ConceptEntity().apply {
                code = "AUDIT-" + UUID.randomUUID().toString().replace("-", "").take(6)
                name = "Sampled audit topic"
                subject = "Mathematics"
                sortOrder = 0
            }
        )
        curriculumMaps.save(
            CurriculumMapEntity().apply {
                conceptId = concept.id
                countryCode = "KE"
                curriculum = "CBC"
                gradeLevel = "Grade 4"
                strandName = "Numbers"
                sortOrder = 0
            }
        )
        val unit = contentUnits.save(
            ContentUnitEntity().apply {
                generationKey = "test:sampled-audit:" + UUID.randomUUID()
                taskType = "NOTES"
                this.title = title
                conceptId = concept.id
                subject = "Mathematics"
                gradeLevel = "Grade 4"
                language = "en"
                body = "A short explanation."
                provenance = "GENERATED"
                confidence = 1.0
                reviewState = "UNREVIEWED"
            }
        )
        val savedSteps = (0..2).map { index ->
            steps.save(
                ContentUnitStepEntity().apply {
                    unitId = unit.id
                    orderIndex = index
                    this.title = "Step " + index
                    body = "Explanation for step " + index
                }
            )
        }
        questions.save(
            ContentUnitQuestionEntity().apply {
                unitId = unit.id
                stepId = savedSteps.last().id
                orderIndex = 0
                qType = "MULTIPLE_CHOICE"
                text = "What is 1/2 of 4?"
                options = "[\"1\",\"2\",\"4\"]"
                correctAnswer = "2"
                explanation = "Because half of four is two."
                points = 2
                difficulty = 3
            }
        )
        return unit
    }

    private fun flushAndClear() {
        entityManager.flush()
        entityManager.clear()
    }

    private fun seed(phone: String, role: Role, name: String): UserEntity =
        users.save(
            UserEntity().apply {
                phoneNumber = phone
                email = phone + "@audit.test"
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
}
