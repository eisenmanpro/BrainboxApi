package com.afrithecus.brainbox.api.content

import com.afrithecus.brainbox.api.auth.web.AuthResponse
import com.afrithecus.brainbox.api.content.entity.ConceptEntity
import com.afrithecus.brainbox.api.content.entity.ContentUnitEntity
import com.afrithecus.brainbox.api.content.entity.ContentUnitStepEntity
import com.afrithecus.brainbox.api.content.entity.CurriculumMapEntity
import com.afrithecus.brainbox.api.content.repository.ConceptRepository
import com.afrithecus.brainbox.api.content.repository.ContentReviewRepository
import com.afrithecus.brainbox.api.content.repository.ContentUnitRepository
import com.afrithecus.brainbox.api.content.repository.ContentUnitStepRepository
import com.afrithecus.brainbox.api.content.repository.CurriculumMapRepository
import com.afrithecus.brainbox.api.content.web.ContentFeedbackPayload
import com.afrithecus.brainbox.api.content.web.ContentReviewPayload
import com.afrithecus.brainbox.api.content.web.ReviewItemPayload
import com.afrithecus.brainbox.api.content.web.ReviewQueuePayload
import com.afrithecus.brainbox.api.content.web.SubmitFeedbackRequest
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.model.Role
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import com.afrithecus.brainbox.api.learning.LearningService
import com.afrithecus.brainbox.api.learning.repository.LearningPostRepository
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

/**
 * Phase 7.4b teacher review surface over HTTP: queue filtering and cursoring, item
 * detail with the validator report, the two-teacher decision flow, decision history,
 * feedback upsert, and teacher-only access.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ReviewSurfaceTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val users: UserRepository,
    @Autowired private val contentUnits: ContentUnitRepository,
    @Autowired private val unitSteps: ContentUnitStepRepository,
    @Autowired private val concepts: ConceptRepository,
    @Autowired private val curriculumMaps: CurriculumMapRepository,
    @Autowired private val reviews: ContentReviewRepository,
    @Autowired private val projection: ContentProjectionService,
    @Autowired private val posts: LearningPostRepository,
    @Autowired private val learningService: LearningService,
    @Autowired private val passwordEncoder: PasswordEncoder,
    @Autowired private val entityManager: EntityManager,
) {

    @Test
    fun `queue filters by state and subject and returns a page`() {
        val teacher = teacher("0744600201")
        val token = token(teacher)
        val maths = contentUnits.save(unit("Fractions", "Mathematics", "UNREVIEWED"))
        contentUnits.save(unit("Photosynthesis", "Biology", "UNREVIEWED"))
        contentUnits.save(unit("Already reviewed", "Mathematics", "REVIEWED"))

        val body = mockMvc.perform(
            get("/teacher/review/queue")
                .param("state", "UNREVIEWED")
                .param("subject", "Mathematics")
                .param("limit", "20")
                .header("Authorization", auth(token))
        ).andExpect(status().isOk).andReturn().response.contentAsString

        val page = objectMapper.readValue(body, ReviewQueuePayload::class.java)
        check(page.items.size == 1) { "expected one item, got " + page.items.size }
        check(page.items.single().contentId == maths.id.toString())
        check(page.items.single().reviewState == "UNREVIEWED")
        check(page.items.single().generated)
        check(page.nextCursor == null)
    }

    @Test
    fun `two distinct teacher approvals resolve the item and appear in the history`() {
        val first = teacher("0744600202")
        val second = teacher("0744600203")
        val unit = contentUnits.save(unit("Angles", "Mathematics", "UNREVIEWED"))

        decide(first, "APPROVE", unit)
        decide(second, "APPROVE", unit)

        val item = read(
            mockMvc.perform(
                get("/teacher/review/UNIT/" + unit.id).header("Authorization", auth(token(first)))
            ).andExpect(status().isOk).andReturn().response.contentAsString,
            ReviewItemPayload::class.java,
        )
        check(item.reviewState == "REVIEWED") { "expected REVIEWED, got " + item.reviewState }
        check(item.reviews.size == 2) { "expected two decisions, got " + item.reviews.size }
        check(item.outcome?.state == "REVIEWED")
        check(item.outcome.autoApproved == false)
        check(item.validation.blockers) // sparse fixture: no steps, so validation blocks

        val history = mockMvc.perform(
            get("/teacher/review/UNIT/" + unit.id + "/decisions").header("Authorization", auth(token(first)))
        ).andExpect(status().isOk).andReturn().response.contentAsString
        val rows = objectMapper.readValue(history, Array<ContentReviewPayload>::class.java)
        check(rows.size == 2)
        check(rows.all { it.decision == "APPROVE" })
        check(reviews.findAllByContentTypeAndContentIdAndContentVersion("UNIT", unit.id, 1).size == 2)
    }

    @Test
    fun `feedback upserts one row per teacher and content item`() {
        val teacher = teacher("0744600204")
        val token = token(teacher)
        val unit = contentUnits.save(unit("Decimals", "Mathematics", "UNREVIEWED"))

        val first = feedback(token, unit, 3)
        val second = feedback(token, unit, 5)

        check(first.id == second.id) { "feedback should update, not duplicate" }
        check(second.score == 5)
        check(second.tags == listOf("clear"))

        val fetched = read(
            mockMvc.perform(
                get("/teacher/content/feedback")
                    .param("contentType", "UNIT")
                    .param("contentId", unit.id.toString())
                    .header("Authorization", auth(token))
            ).andExpect(status().isOk).andReturn().response.contentAsString,
            ContentFeedbackPayload::class.java,
        )
        check(fetched.id == first.id && fetched.score == 5)
    }

    @Test
    fun `the review surface requires a teacher token`() {
        val unit = contentUnits.save(unit("Protected", "Mathematics", "UNREVIEWED"))

        mockMvc.perform(get("/teacher/review/queue")).andExpect(status().isUnauthorized)

        val learner = learner("0744600205")
        mockMvc.perform(
            get("/teacher/review/queue").header("Authorization", auth(token(learner)))
        ).andExpect(status().isForbidden)

        mockMvc.perform(
            get("/teacher/review/UNIT/" + unit.id).header("Authorization", auth(token(learner)))
        ).andExpect(status().isForbidden)
    }

    @Test
    fun `a unit that fails a gate stays UNREVIEWED, projects hidden and stays in the queue`() {
        val teacher = teacher("0744600206")
        val token = token(teacher)
        val unit = seedGatedUnit("Warning item")

        projection.project(unit.id)

        entityManager.flush()
        entityManager.clear()

        check(contentUnits.findById(unit.id).orElseThrow().reviewState == "UNREVIEWED")
        check(!posts.findById(unit.id).orElseThrow().isPublished)

        val body = mockMvc.perform(
            get("/teacher/review/queue")
                .param("state", "UNREVIEWED")
                .param("limit", "20")
                .header("Authorization", auth(token))
        ).andExpect(status().isOk).andReturn().response.contentAsString
        val page = objectMapper.readValue(body, ReviewQueuePayload::class.java)
        check(page.items.any { it.contentId == unit.id.toString() }) { "a gated unit must remain in the exception queue" }
    }

    @Test
    fun `a human approval re-projects a hidden unit and makes it learner-visible`() {
        val first = teacher("0744600207")
        val second = teacher("0744600208")
        val unit = seedGatedUnit("Hidden geometry")
        projection.project(unit.id)
        entityManager.flush()
        entityManager.clear()
        check(!posts.findById(unit.id).orElseThrow().isPublished)

        decide(first, "APPROVE", unit)
        decide(second, "APPROVE", unit)

        entityManager.flush()
        entityManager.clear()
        check(contentUnits.findById(unit.id).orElseThrow().reviewState == "REVIEWED")
        check(posts.findById(unit.id).orElseThrow().isPublished)

        val detail = learningService.detail(learner("0744600209"), unit.id.toString())
        check(detail.isPublished)
    }

    // ---------------------------------------------------------------- fixtures

    private fun decide(actor: UserEntity, decision: String, unit: ContentUnitEntity) {
        mockMvc.perform(
            post("/teacher/review/UNIT/" + unit.id + "/decision")
                .header("Authorization", auth(token(actor)))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"decision\":\"" + decision + "\",\"comment\":\"looks good\"}")
        ).andExpect(status().isOk)
    }

    private fun feedback(token: String, unit: ContentUnitEntity, score: Int): ContentFeedbackPayload {
        val body = objectMapper.writeValueAsString(
            SubmitFeedbackRequest(
                contentType = "UNIT",
                contentId = unit.id.toString(),
                score = score,
                tags = listOf("clear"),
                comment = "useful for revision",
            )
        )
        return read(
            mockMvc.perform(
                post("/teacher/content/feedback").header("Authorization", auth(token))
                    .contentType(MediaType.APPLICATION_JSON).content(body)
            ).andExpect(status().isOk).andReturn().response.contentAsString,
            ContentFeedbackPayload::class.java,
        )
    }

    private fun unit(title: String, subject: String, state: String): ContentUnitEntity =
        ContentUnitEntity().apply {
            generationKey = "test:review-surface:" + UUID.randomUUID()
            taskType = "NOTES"
            this.title = title
            this.subject = subject
            gradeLevel = "Grade 4"
            language = "en"
            body = "Body for " + title
            provenance = "GENERATED"
            reviewState = state
            status = "DRAFT"
        }

    /**
     * A generated-looking unit whose only validation finding is a warning: it has a
     * title, three explained steps and a curriculum mapping but no questions. Under
     * 7.5c that is below the 1.0 validator bar, so the machine leaves it hidden and
     * UNREVIEWED for the exception queue.
     */
    private fun seedGatedUnit(title: String): ContentUnitEntity {
        val concept = concepts.save(
            ConceptEntity().apply {
                code = "REVIEW-" + UUID.randomUUID().toString().replace("-", "").substring(0, 6)
                name = "Fractions"
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
            }
        )
        val unit = contentUnits.save(unit(title, "Mathematics", "UNREVIEWED").apply { conceptId = concept.id })
        repeat(3) { index ->
            unitSteps.save(
                ContentUnitStepEntity().apply {
                    unitId = unit.id
                    orderIndex = index
                    this.title = "Step " + index
                    body = "Explanation for step " + index
                }
            )
        }
        return unit
    }

    private fun teacher(phone: String) = seed(phone, Role.TEACHER, "Review Teacher")

    private fun learner(phone: String) = seed(phone, Role.STUDENT, "Review Learner")

    private fun seed(phone: String, role: Role, name: String): UserEntity =
        users.save(
            UserEntity().apply {
                phoneNumber = phone
                email = phone + "@review.test"
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
