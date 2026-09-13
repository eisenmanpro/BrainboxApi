package com.afrithecus.brainbox.api.recommendation

import com.afrithecus.brainbox.api.auth.web.AuthResponse
import com.afrithecus.brainbox.api.identity.entity.SchoolEntity
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.model.Role
import com.afrithecus.brainbox.api.identity.repository.SchoolRepository
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import com.afrithecus.brainbox.api.learning.entity.LearningPostEntity
import com.afrithecus.brainbox.api.learning.entity.LearningProgressEntity
import com.afrithecus.brainbox.api.learning.model.LearningScope
import com.afrithecus.brainbox.api.learning.repository.LearningPostRepository
import com.afrithecus.brainbox.api.learning.repository.LearningProgressRepository
import com.afrithecus.brainbox.api.mastery.entity.TopicMasteryEntity
import com.afrithecus.brainbox.api.mastery.repository.TopicMasteryRepository
import com.afrithecus.brainbox.api.recommendation.entity.RecommendationInteractionEntity
import com.afrithecus.brainbox.api.recommendation.repository.RecommendationInteractionRepository
import com.afrithecus.brainbox.api.recommendation.web.RecommendationDto
import com.afrithecus.brainbox.api.recommendation.web.RecommendationsResponseDto
import org.junit.jupiter.api.BeforeEach
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
import java.time.Instant
import java.util.UUID

/** Server-derived recommendations (docs/ongoing/api_recommendations_changes.md). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class RecommendationsWebTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val userRepository: UserRepository,
    @Autowired private val schoolRepository: SchoolRepository,
    @Autowired private val postRepository: LearningPostRepository,
    @Autowired private val progressRepository: LearningProgressRepository,
    @Autowired private val masteryRepository: TopicMasteryRepository,
    @Autowired private val interactionRepository: RecommendationInteractionRepository,
    @Autowired private val passwordEncoder: PasswordEncoder,
) {

    private lateinit var school: SchoolEntity
    private lateinit var learner: UserEntity
    private lateinit var peer: UserEntity
    private var postContinue: LearningPostEntity? = null
    private var postWeak: LearningPostEntity? = null
    private var postPeer: LearningPostEntity? = null

    private fun user(role: Role, name: String, phone: String): UserEntity {
        val entity = UserEntity()
        entity.phoneNumber = phone
        entity.email = phone + "@rec.test"
        entity.passwordHash = passwordEncoder.encode("password123") ?: error("encode")
        entity.name = name
        entity.role = role
        entity.schoolId = school.id
        entity.isActive = true
        entity.isVerified = true
        return userRepository.save(entity)
    }

    private fun post(title: String, subject: String, views: Int): LearningPostEntity =
        postRepository.saveAndFlush(LearningPostEntity().apply {
            this.title = title
            this.subject = subject
            scope = LearningScope.GLOBAL
            isPublished = true
            viewCount = views
            createdBy = learner.id
        })

    private fun token(entity: UserEntity): String {
        val response = mockMvc.perform(
            post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"identifier\":\"" + entity.phoneNumber + "\",\"password\":\"password123\"}")
        ).andReturn().response
        check(response.status == 200) { "login failed: " + response.status + " " + response.contentAsString }
        return objectMapper.readValue(response.contentAsString, AuthResponse::class.java).sessionToken!!
    }

    @BeforeEach
    fun setUp() {
        school = schoolRepository.save(SchoolEntity().apply { name = "Alliance High School"; isActive = true })
        learner = user(Role.STUDENT, "Alice Learner", "0755700001")
        peer = user(Role.STUDENT, "Bob Peer", "0755700002")
        postContinue = post("Algebra Basics", "Mathematics", 10)
        postWeak = post("Fractions Refresher", "Mathematics", 20)
        postPeer = post("Newton's Laws", "Physics", 5)
        post("Poetry Guide", "English", 100)

        progressRepository.saveAndFlush(LearningProgressEntity().apply {
            userId = learner.id
            postId = postContinue!!.id
            quizScore = 40
            completed = false
            lastViewedAt = Instant.now()
        })
        masteryRepository.saveAndFlush(TopicMasteryEntity().apply {
            userId = learner.id
            topicId = "algebra"
            topicName = "Algebra"
            subject = "Mathematics"
            score = 30.0
        })
        interactionRepository.saveAndFlush(RecommendationInteractionEntity().apply {
            userId = peer.id
            postId = postPeer!!.id
            interactionType = "complete"
            interactionScore = 100.0
            occurredAt = Instant.now()
            schoolId = school.id
        })
    }

    @Test
    fun `personalized response carries types and a trending array`() {
        val t = token(learner)
        val response = objectMapper.readValue(
            mockMvc.perform(get("/recommendations/user/" + learner.id).header("Authorization", "Bearer " + t))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            RecommendationsResponseDto::class.java,
        )
        check(response.trending.isNotEmpty())
        val types = response.recommendations.map { it.type }.toSet()
        check("CONTINUE_LEARNING" in types) { "types=" + types }
        check("TOPIC_BASED" in types) { "types=" + types }
        check("COLLABORATIVE" in types) { "types=" + types }
        check(response.recommendations.all { it.confidenceScore in 0.0f..1.0f })
        // De-duplicated by postId.
        check(response.recommendations.map { it.postId }.toSet().size == response.recommendations.size)
    }

    @Test
    fun `trending is school-scoped and interaction replay is de-duplicated`() {
        val t = token(learner)
        val trending = objectMapper.readValue(
            mockMvc.perform(
                get("/recommendations/trending").param("schoolId", school.id.toString())
                    .header("Authorization", "Bearer " + t)
            ).andExpect(status().isOk).andReturn().response.contentAsString,
            Array<RecommendationDto>::class.java,
        )
        check(trending.isNotEmpty() && trending.all { it.type == "TRENDING" })

        val body = "{\"userId\":\"" + peer.id + "\",\"postId\":\"" + postWeak!!.id +
            "\",\"interactionType\":\"complete\",\"timeSpentSeconds\":240,\"interactionScore\":100.0,\"timestamp\":1748822400000}"
        mockMvc.perform(
            post("/recommendations/interaction").header("Authorization", "Bearer " + t)
                .contentType(MediaType.APPLICATION_JSON).content(body)
        ).andExpect(status().isNoContent)
        mockMvc.perform(
            post("/recommendations/interaction").header("Authorization", "Bearer " + t)
                .contentType(MediaType.APPLICATION_JSON).content(body)
        ).andExpect(status().isNoContent)
        check(interactionRepository.findAllByUserId(learner.id).size == 1)
    }

    @Test
    fun `another user's recommendations are forbidden`() {
        val t = token(learner)
        mockMvc.perform(get("/recommendations/user/" + peer.id).header("Authorization", "Bearer " + t))
            .andExpect(status().isForbidden)
    }
}
