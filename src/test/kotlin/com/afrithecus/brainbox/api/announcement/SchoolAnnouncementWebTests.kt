package com.afrithecus.brainbox.api.announcement

import com.afrithecus.brainbox.api.announcement.web.SchoolAnnouncementAnalyticsPayload
import com.afrithecus.brainbox.api.announcement.web.SchoolAnnouncementPayload
import com.afrithecus.brainbox.api.auth.web.AuthResponse
import com.afrithecus.brainbox.api.identity.entity.SchoolEntity
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.model.Role
import com.afrithecus.brainbox.api.identity.repository.SchoolRepository
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.util.UUID

/** Admin school announcements CRUD (docs/ongoing/open_gaps.md ANN-1). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class SchoolAnnouncementWebTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val userRepository: UserRepository,
    @Autowired private val schoolRepository: SchoolRepository,
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

    private fun user(role: Role, phone: String): UserEntity = userRepository.save(UserEntity().apply {
        phoneNumber = phone
        email = phone + "@schoolann.test"
        passwordHash = passwordEncoder.encode("password123") ?: error("encode")
        name = "Admin User"
        this.role = role
        isActive = true
        isVerified = true
    })

    private fun token(user: UserEntity): String {
        val body = mockMvc.perform(
            post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"identifier\":\"" + user.email + "\",\"password\":\"password123\"}")
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return objectMapper.readValue(body, AuthResponse::class.java).sessionToken!!
    }

    private fun auth(token: String) = "Bearer " + token

    @Test
    fun `admin announcements crud is scoped and repeat safe`() {
        val admin = user(Role.ADMIN, "0755030001")
        val teacher = user(Role.TEACHER, "0755030002")
        val t = token(admin)

        val request = SchoolAnnouncementPayload(
            announcementId = "",
            title = "Sports day",
            body = "Sports day is on Friday.",
            audience = "All Parents",
        )
        val created = objectMapper.readValue(
            mockMvc.perform(post("/admin/schools/" + schoolId + "/announcements").header("Authorization", auth(t))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            SchoolAnnouncementPayload::class.java,
        )
        check(created.announcementId.isNotBlank())
        check(created.postedBy == "Admin User")
        check(created.postedAt > 0)

        val listed = objectMapper.readValue(
            mockMvc.perform(get("/admin/schools/" + schoolId + "/announcements").header("Authorization", auth(t)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            Array<SchoolAnnouncementPayload>::class.java,
        )
        check(listed.size == 1)

        val updated = objectMapper.readValue(
            mockMvc.perform(put("/admin/schools/" + schoolId + "/announcements/" + created.announcementId)
                .header("Authorization", auth(t))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request.copy(title = "Sports day moved"))))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            SchoolAnnouncementPayload::class.java,
        )
        check(updated.title == "Sports day moved")

        // A non-admin cannot read or write, and an unknown school is not found.
        mockMvc.perform(get("/admin/schools/" + schoolId + "/announcements")
            .header("Authorization", auth(token(teacher))))
            .andExpect(status().isForbidden)
        mockMvc.perform(get("/admin/schools/" + UUID.randomUUID() + "/announcements").header("Authorization", auth(t)))
            .andExpect(status().isNotFound)

        mockMvc.perform(delete("/admin/schools/" + schoolId + "/announcements/" + created.announcementId)
            .header("Authorization", auth(t)))
            .andExpect(status().isNoContent)
        mockMvc.perform(delete("/admin/schools/" + schoolId + "/announcements/" + created.announcementId)
            .header("Authorization", auth(t)))
            .andExpect(status().isNoContent)
        val after = objectMapper.readValue(
            mockMvc.perform(get("/admin/schools/" + schoolId + "/announcements").header("Authorization", auth(t)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            Array<SchoolAnnouncementPayload>::class.java,
        )
        check(after.isEmpty())
    }

    private fun member(role: Role, phone: String, name: String, gradeLevel: String? = null): UserEntity =
        userRepository.save(UserEntity().apply {
            phoneNumber = phone
            email = phone + "@schoolann.test"
            passwordHash = passwordEncoder.encode("password123") ?: error("encode")
            this.name = name
            this.role = role
            this.gradeLevel = gradeLevel
            this@SchoolAnnouncementWebTests.schoolId.let { this.schoolId = it }
            isActive = true
            isVerified = true
        })

    @Test
    fun `announcement analytics counts totals and audience reach`() {
        val admin = user(Role.ADMIN, "0755030010")
        val t = token(admin)
        val studentA = member(Role.STUDENT, "0755030011", "Ann Learner", "Grade 4")
        member(Role.STUDENT, "0755030012", "Ben Learner", "Grade 4")
        member(Role.STUDENT, "0755030013", "Cara Learner", "Grade 5")
        val parent = member(Role.PARENT, "0755030014", "Dora Parent")
        member(Role.TEACHER, "0755030015", "Evan Teacher")
        studentA.parentUserId = parent.id
        userRepository.save(studentA)

        fun publish(title: String, audience: String, meeting: Long? = null): SchoolAnnouncementPayload {
            val request = SchoolAnnouncementPayload(
                announcementId = "",
                title = title,
                body = "Body",
                audience = audience,
                scheduledMeetingDate = meeting,
            )
            return objectMapper.readValue(
                mockMvc.perform(post("/admin/schools/" + schoolId + "/announcements").header("Authorization", auth(t))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk).andReturn().response.contentAsString,
                SchoolAnnouncementPayload::class.java,
            )
        }

        publish("Everyone", "All")
        publish("Grade fours", "Grades: 4")
        publish("Teachers only", "Teachers: Evan Teacher")
        publish("Meeting", "All", meeting = System.currentTimeMillis() + 86_400_000L)

        val analytics = objectMapper.readValue(
            mockMvc.perform(
                get("/admin/schools/" + schoolId + "/announcements/analytics").header("Authorization", auth(t))
            ).andExpect(status().isOk).andReturn().response.contentAsString,
            SchoolAnnouncementAnalyticsPayload::class.java,
        )
        check(analytics.totalAnnouncements == 4)
        check(analytics.announcementsLast30Days == 4)
        check(analytics.scheduledMeetings == 1)
        check(analytics.latestPostedAt != null)
        val byTitle = analytics.announcements.associateBy { it.title }
        // Roster is five active accounts: three students, one parent, one teacher.
        check(byTitle.getValue("Everyone").estimatedReach == 5)
        // Grade 4 students (2) plus the parent linked to one of them.
        check(byTitle.getValue("Grade fours").estimatedReach == 3)
        check(byTitle.getValue("Teachers only").estimatedReach == 1)

        // A non-admin cannot read analytics.
        val teacher = user(Role.TEACHER, "0755030016")
        mockMvc.perform(
            get("/admin/schools/" + schoolId + "/announcements/analytics")
                .header("Authorization", auth(token(teacher)))
        ).andExpect(status().isForbidden)
    }
}
