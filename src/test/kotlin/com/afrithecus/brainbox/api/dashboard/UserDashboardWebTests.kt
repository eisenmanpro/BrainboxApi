package com.afrithecus.brainbox.api.dashboard

import com.afrithecus.brainbox.api.auth.web.AuthResponse
import com.afrithecus.brainbox.api.dashboard.web.DashboardInsightsPayload
import com.afrithecus.brainbox.api.identity.entity.SchoolEntity
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.model.Role
import com.afrithecus.brainbox.api.identity.model.SubscriptionStatus
import com.afrithecus.brainbox.api.identity.model.SubscriptionTier
import com.afrithecus.brainbox.api.identity.repository.SchoolRepository
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import com.afrithecus.brainbox.api.recommendation.web.RecommendationsResponseDto
import com.afrithecus.brainbox.api.subscription.repository.SubscriptionRepository
import com.afrithecus.brainbox.api.subscription.web.SubscriptionPayload
import com.afrithecus.brainbox.api.user.web.UserProfilePayload
import com.afrithecus.brainbox.api.user.web.UserProgressPayload
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
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Dashboard contract 14 supporting endpoints: user profile/progress,
 * subscriptions/me, the recommendations rail and the insights shoutout timestamp.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class UserDashboardWebTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val userRepository: UserRepository,
    @Autowired private val schoolRepository: SchoolRepository,
    @Autowired private val subscriptionRepository: SubscriptionRepository,
    @Autowired private val passwordEncoder: PasswordEncoder,
) {

    private lateinit var schoolId: UUID

    @BeforeEach
    fun setUp() {
        val school = SchoolEntity()
        school.name = "Alliance High School"
        school.isActive = true
        schoolRepository.save(school)
        schoolId = school.id
    }

    private fun student(name: String, phone: String, grade: String): UserEntity {
        val user = UserEntity()
        user.phoneNumber = phone
        user.email = phone + "@dashboard.test"
        user.passwordHash = passwordEncoder.encode("password123") ?: error("encode")
        user.name = name
        user.role = Role.STUDENT
        user.schoolId = schoolId
        user.gradeLevel = grade
        user.studentAdmissionNumber = "ADM-" + phone.takeLast(4)
        user.isVerified = true
        user.isActive = true
        return userRepository.save(user)
    }

    private fun token(user: UserEntity): String {
        val login = mockMvc.perform(
            post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"identifier\":\"${user.email}\",\"password\":\"password123\"}")
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return objectMapper.readValue(login, AuthResponse::class.java).sessionToken!!
    }

    private fun auth(token: String) = "Bearer " + token

    @Test
    fun `profile status, progress and subscription follow the account state`() {
        val student = student("Alice Mwangi", "0711000101", "Grade 4")
        val token = token(student)

        val profile = objectMapper.readValue(
            mockMvc.perform(get("/users/${student.id}/profile").header("Authorization", auth(token)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            UserProfilePayload::class.java,
        )
        check(profile.id == student.id.toString())
        check(profile.name == "Alice Mwangi")
        check(profile.grade == 4)
        check(profile.subscriptionStatus == "Expired")

        // Active subscription -> Active, then within the expiry window -> Expiring.
        val subscription = subscriptionRepository.findByUserId(student.id) ?: error("subscription missing")
        subscription.tier = SubscriptionTier.EXPLORER
        subscription.status = SubscriptionStatus.ACTIVE
        subscription.expiryDate = Instant.now().plus(Duration.ofDays(30))
        subscriptionRepository.save(subscription)

        val active = objectMapper.readValue(
            mockMvc.perform(get("/users/${student.id}/profile").header("Authorization", auth(token)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            UserProfilePayload::class.java,
        )
        check(active.subscriptionStatus == "Active")

        subscription.expiryDate = Instant.now().plus(Duration.ofDays(3))
        subscriptionRepository.save(subscription)
        val expiring = objectMapper.readValue(
            mockMvc.perform(get("/users/${student.id}/profile").header("Authorization", auth(token)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            UserProfilePayload::class.java,
        )
        check(expiring.subscriptionStatus == "Expiring")

        val me = objectMapper.readValue(
            mockMvc.perform(get("/subscriptions/me").header("Authorization", auth(token)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            SubscriptionPayload::class.java,
        )
        check(me.userId == student.id.toString())
        check(me.tier == "EXPLORER")
        check(me.status == "ACTIVE")

        val progress = objectMapper.readValue(
            mockMvc.perform(get("/users/${student.id}/progress").header("Authorization", auth(token)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            UserProgressPayload::class.java,
        )
        check(progress.userId == student.id.toString())
        check(progress.level >= 1)
        check(progress.xp == 0)
        check(progress.streakDays == 0)

        val recommendations = objectMapper.readValue(
            mockMvc.perform(get("/recommendations/user/${student.id}").header("Authorization", auth(token)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            RecommendationsResponseDto::class.java,
        )
        check(recommendations.lastUpdated > 0)
        check(recommendations.recommendations.size <= 10)

        mockMvc.perform(
            get("/recommendations/trending").param("schoolId", schoolId.toString()).header("Authorization", auth(token))
        ).andExpect(status().isOk)

        val insights = objectMapper.readValue(
            mockMvc.perform(get("/dashboard/insights").header("Authorization", auth(token)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            DashboardInsightsPayload::class.java,
        )
        check(insights.teacherShoutout.sentAt == 0L)
        check(insights.teacherShoutout.teacherName.isEmpty())
    }

    @Test
    fun `another user's profile, progress and recommendations are forbidden`() {
        val alice = student("Alice Mwangi", "0711000201", "Grade 4")
        val bob = student("Bob Otieno", "0711000202", "Grade 4")
        val token = token(alice)

        mockMvc.perform(get("/users/${bob.id}/profile").header("Authorization", auth(token)))
            .andExpect(status().isForbidden)
        mockMvc.perform(get("/users/${bob.id}/progress").header("Authorization", auth(token)))
            .andExpect(status().isForbidden)
        mockMvc.perform(get("/recommendations/user/${bob.id}").header("Authorization", auth(token)))
            .andExpect(status().isForbidden)
    }
}
