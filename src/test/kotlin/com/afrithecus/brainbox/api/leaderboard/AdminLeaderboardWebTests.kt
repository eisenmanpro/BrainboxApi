package com.afrithecus.brainbox.api.leaderboard

import com.afrithecus.brainbox.api.achievements.entity.BadgeEntity
import com.afrithecus.brainbox.api.achievements.entity.UserAchievementsEntity
import com.afrithecus.brainbox.api.achievements.entity.UserBadgeEntity
import com.afrithecus.brainbox.api.achievements.entity.XpEventEntity
import com.afrithecus.brainbox.api.achievements.repository.BadgeRepository
import com.afrithecus.brainbox.api.achievements.repository.UserAchievementsRepository
import com.afrithecus.brainbox.api.achievements.repository.UserBadgeRepository
import com.afrithecus.brainbox.api.achievements.repository.XpEventRepository
import com.afrithecus.brainbox.api.auth.web.AuthResponse
import com.afrithecus.brainbox.api.identity.entity.SchoolEntity
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.model.Role
import com.afrithecus.brainbox.api.identity.model.SubRole
import com.afrithecus.brainbox.api.identity.repository.SchoolRepository
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import com.afrithecus.brainbox.api.leaderboard.web.AdminLeaderboardEntryPayload
import com.afrithecus.brainbox.api.mastery.entity.TopicMasteryEntity
import com.afrithecus.brainbox.api.mastery.repository.TopicMasteryRepository
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
 * Admin leaderboard management (doc 08 section 7 / Phase 5): XP and subject-mastery
 * rankings, school/grade/timeframe scoping, the season reset and the XP recalculation,
 * plus the admin authorization boundaries.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AdminLeaderboardWebTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val userRepository: UserRepository,
    @Autowired private val schoolRepository: SchoolRepository,
    @Autowired private val passwordEncoder: PasswordEncoder,
    @Autowired private val xpEventRepository: XpEventRepository,
    @Autowired private val userAchievementsRepository: UserAchievementsRepository,
    @Autowired private val userBadgeRepository: UserBadgeRepository,
    @Autowired private val badgeRepository: BadgeRepository,
    @Autowired private val topicMasteryRepository: TopicMasteryRepository,
) {

    private lateinit var schoolId: UUID
    private lateinit var otherSchoolId: UUID

    @BeforeEach
    fun setUp() {
        schoolId = school("Alliance High School")
        otherSchoolId = school("Mangu High School")
    }

    private fun school(name: String): UUID {
        val entity = SchoolEntity()
        entity.name = name
        entity.isActive = true
        schoolRepository.save(entity)
        return entity.id
    }

    private fun user(
        role: Role,
        name: String,
        phone: String,
        school: UUID = schoolId,
        grade: String? = null,
        subRole: SubRole? = null,
    ): UserEntity {
        val entity = UserEntity()
        entity.phoneNumber = phone
        entity.email = phone + "@leaderboard.test"
        entity.passwordHash = passwordEncoder.encode("password123") ?: error("encode")
        entity.name = name
        entity.role = role
        entity.subRole = subRole
        entity.schoolId = school
        entity.gradeLevel = grade
        entity.isVerified = true
        entity.isActive = true
        return userRepository.save(entity)
    }

    private fun token(entity: UserEntity): String {
        val body = objectMapper.writeValueAsString(mapOf("identifier" to entity.email, "password" to "password123"))
        val login = mockMvc.perform(
            post("/auth/login").contentType(MediaType.APPLICATION_JSON).content(body)
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return objectMapper.readValue(login, AuthResponse::class.java).sessionToken!!
    }

    private fun auth(token: String) = "Bearer " + token

    private fun xp(user: UserEntity, amount: Int, at: Instant = Instant.now()) {
        xpEventRepository.save(XpEventEntity().apply {
            userId = user.id
            this.amount = amount
            activityType = "TEST"
            createdAt = at
        })
    }

    private fun achievements(user: UserEntity, totalXp: Int) {
        userAchievementsRepository.save(UserAchievementsEntity().apply {
            userId = user.id
            this.totalXp = totalXp
        })
    }

    private fun grantBadge(user: UserEntity, title: String) {
        val badge = badgeRepository.save(BadgeEntity().apply {
            this.title = title
            icon = "T"
            description = "Test badge"
        })
        userBadgeRepository.save(UserBadgeEntity().apply {
            userId = user.id
            badgeId = badge.id
        })
    }

    private fun mastery(user: UserEntity, subject: String, score: Double, topic: String) {
        topicMasteryRepository.save(TopicMasteryEntity().apply {
            userId = user.id
            topicId = topic
            topicName = topic
            this.subject = subject
            this.score = score
            previousScore = score
            attemptsCount = 1
            questionsAttempted = 1
            correctAnswers = 1
        })
    }

    private fun leaderboard(token: String, query: String = ""): List<AdminLeaderboardEntryPayload> {
        val response = mockMvc.perform(
            get("/admin/leaderboard" + query).header("Authorization", auth(token))
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return objectMapper.readValue(response, Array<AdminLeaderboardEntryPayload>::class.java).toList()
    }

    @Test
    fun `admin leaderboard ranks xp and scopes by school and grade with badge counts`() {
        val admin = user(Role.ADMIN, "Platform Admin", "0700000700")
        val alice = user(Role.STUDENT, "Alice", "0700000701", grade = "Grade 4")
        val bob = user(Role.STUDENT, "Bob", "0700000702", grade = "Grade 4")
        val carol = user(Role.STUDENT, "Carol", "0700000703", grade = "Grade 5")
        val dave = user(Role.STUDENT, "Dave", "0700000704", school = otherSchoolId, grade = "Grade 4")
        xp(alice, 300); xp(bob, 100); xp(carol, 200); xp(dave, 500)
        grantBadge(alice, "Early Bird")
        val adminToken = token(admin)

        val global = leaderboard(adminToken)
        check(global.map { it.studentName } == listOf("Dave", "Alice", "Carol", "Bob"))
        check(global.first { it.studentName == "Dave" }.schoolName == "Mangu High School")
        check(global.first { it.studentName == "Alice" }.badgeCount == 1)
        check(global.first { it.studentName == "Alice" }.gradeLevel == "Grade 4")
        check(global.map { it.rank } == listOf(1, 2, 3, 4))

        val schoolOnly = leaderboard(adminToken, "?schoolId=" + schoolId)
        check(schoolOnly.map { it.studentName } == listOf("Alice", "Carol", "Bob"))

        val gradeFive = leaderboard(adminToken, "?schoolId=" + schoolId + "&grade=Grade 5")
        check(gradeFive.map { it.studentName } == listOf("Carol"))

        val gradeFour = leaderboard(adminToken, "?grade=Grade 4")
        check(gradeFour.map { it.studentName } == listOf("Dave", "Alice", "Bob"))
    }

    @Test
    fun `timeframe windows the xp and the subject board ranks on mastery`() {
        val admin = user(Role.ADMIN, "Platform Admin Two", "0700000710")
        val alice = user(Role.STUDENT, "Alice Week", "0700000711", grade = "Grade 6")
        val bob = user(Role.STUDENT, "Bob Old", "0700000712", grade = "Grade 6")
        xp(alice, 100, Instant.now().minus(Duration.ofDays(1)))
        xp(bob, 100, Instant.now().minus(Duration.ofDays(10)))
        val adminToken = token(admin)

        check(leaderboard(adminToken, "?timeframe=WEEKLY").map { it.studentName } == listOf("Alice Week"))
        check(leaderboard(adminToken, "?timeframe=MONTHLY").map { it.studentName }.toSet() == setOf("Alice Week", "Bob Old"))

        mastery(alice, "Mathematics", 80.0, "fractions")
        mastery(alice, "Mathematics", 100.0, "decimals")
        mastery(bob, "Mathematics", 70.0, "fractions")
        val subjectBoard = leaderboard(adminToken, "?subject=Mathematics")
        check(subjectBoard.map { it.studentName } == listOf("Alice Week", "Bob Old"))
        check(subjectBoard.first().score == 90.0)
    }

    @Test
    fun `recalculate rebuilds total xp and reset starts a new season`() {
        val admin = user(Role.ADMIN, "Platform Admin Three", "0700000720")
        val alice = user(Role.STUDENT, "Alice Reset", "0700000721", grade = "Grade 7")
        xp(alice, 200)
        achievements(alice, 0)
        val adminToken = token(admin)

        mockMvc.perform(post("/admin/leaderboard/recalculate").header("Authorization", auth(adminToken)))
            .andExpect(status().isNoContent)
        check(userAchievementsRepository.findByUserId(alice.id)!!.totalXp == 200)

        mockMvc.perform(
            post("/admin/leaderboard/reset").param("timeframe", "WEEKLY").header("Authorization", auth(adminToken))
        ).andExpect(status().isNoContent)
        // The season starts now, so XP earned before it drops out of the weekly board.
        check(leaderboard(adminToken, "?timeframe=WEEKLY").isEmpty())

        mockMvc.perform(
            post("/admin/leaderboard/reset").param("timeframe", "ALL").header("Authorization", auth(adminToken))
        ).andExpect(status().isNoContent)
        check(userAchievementsRepository.findByUserId(alice.id)!!.totalXp == 0)
    }

    @Test
    fun `ict admin is scoped to its own school and cannot reset`() {
        val ictAdmin = user(Role.TEACHER, "ICT Admin", "0700000730", subRole = SubRole.ICT_ADMIN)
        val alice = user(Role.STUDENT, "Alice Scope", "0700000731", grade = "Grade 8")
        val dave = user(Role.STUDENT, "Dave Scope", "0700000732", school = otherSchoolId, grade = "Grade 8")
        xp(alice, 100); xp(dave, 400)
        val ictToken = token(ictAdmin)

        val scoped = leaderboard(ictToken)
        check(scoped.map { it.studentName } == listOf("Alice Scope"))
        // The schoolId query param cannot widen access.
        check(leaderboard(ictToken, "?schoolId=" + otherSchoolId).map { it.studentName } == listOf("Alice Scope"))

        mockMvc.perform(post("/admin/leaderboard/reset").header("Authorization", auth(ictToken)))
            .andExpect(status().isForbidden)
        mockMvc.perform(post("/admin/leaderboard/recalculate").header("Authorization", auth(ictToken)))
            .andExpect(status().isForbidden)
    }

    @Test
    fun `non admin cannot read the leaderboard`() {
        val student = user(Role.STUDENT, "Nosy", "0700000740", grade = "Grade 4")
        mockMvc.perform(get("/admin/leaderboard").header("Authorization", auth(token(student))))
            .andExpect(status().isForbidden)
    }
}
