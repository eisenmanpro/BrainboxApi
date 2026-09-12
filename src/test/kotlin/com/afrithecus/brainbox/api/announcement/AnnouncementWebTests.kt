package com.afrithecus.brainbox.api.announcement

import com.afrithecus.brainbox.api.announcement.web.AnnouncementAnalyticsPayload
import com.afrithecus.brainbox.api.announcement.web.TeacherAnnouncementPayload
import com.afrithecus.brainbox.api.auth.web.AuthResponse
import com.afrithecus.brainbox.api.classes.entity.ClassMembershipEntity
import com.afrithecus.brainbox.api.classes.entity.TeacherClassEntity
import com.afrithecus.brainbox.api.classes.repository.ClassMembershipRepository
import com.afrithecus.brainbox.api.classes.repository.TeacherClassRepository
import com.afrithecus.brainbox.api.identity.entity.SchoolEntity
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.model.Role
import com.afrithecus.brainbox.api.identity.repository.SchoolRepository
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import com.afrithecus.brainbox.api.notification.web.AppNotificationPayload
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
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.util.UUID

/** Teacher announcements CRUD, fan-out, scheduling/expiry and analytics. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AnnouncementWebTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val userRepository: UserRepository,
    @Autowired private val schoolRepository: SchoolRepository,
    @Autowired private val classRepository: TeacherClassRepository,
    @Autowired private val membershipRepository: ClassMembershipRepository,
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

    private fun user(role: Role, name: String, phone: String, parentId: UUID? = null, grade: String? = null): UserEntity {
        val entity = UserEntity()
        entity.phoneNumber = phone
        entity.email = phone + "@ann.test"
        entity.passwordHash = passwordEncoder.encode("password123") ?: error("encode")
        entity.name = name
        entity.role = role
        entity.schoolId = schoolId
        entity.parentUserId = parentId
        entity.gradeLevel = grade
        entity.isVerified = true
        entity.isActive = true
        return userRepository.save(entity)
    }

    private fun token(user: UserEntity): String {
        val body = mockMvc.perform(
            post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"identifier\":\"${user.email}\",\"password\":\"password123\"}")
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return objectMapper.readValue(body, AuthResponse::class.java).sessionToken!!
    }

    private fun auth(token: String) = "Bearer " + token

    private fun announcement(id: String, classId: String, scheduledAt: Long? = null, expiresAt: Long? = null): TeacherAnnouncementPayload =
        TeacherAnnouncementPayload(
            id = id,
            teacherId = "",
            title = "Sports day",
            content = "Sports day is on Friday.",
            type = "EVENT",
            audience = "CLASS",
            targetClassIds = listOf(classId),
            isPriority = true,
            scheduledAt = scheduledAt,
            expiresAt = expiresAt,
        )

    @Test
    fun `create fans out to the class, holds scheduled, hides expired and deletes safely`() {
        val parent = user(Role.PARENT, "Parent One", "0755000100")
        val alice = user(Role.STUDENT, "Alice Mwangi", "0755000101", parentId = parent.id, grade = "Grade 4")
        val teacher1 = user(Role.TEACHER, "Class Teacher", "0755000102", grade = "Grade 4")
        val teacher2 = user(Role.TEACHER, "Other Teacher", "0755000103", grade = "Grade 5")
        val clazz = classRepository.save(TeacherClassEntity().apply {
            teacherUserId = teacher1.id
            this.schoolId = this@AnnouncementWebTests.schoolId
            name = "Grade 4 South"
            gradeLevel = "Grade 4"
            subject = "Mathematics"
            isActive = true
        })
        membershipRepository.save(ClassMembershipEntity().apply {
            classId = clazz.id
            studentId = alice.id
        })

        val t1 = token(teacher1)
        val t2 = token(teacher2)
        val aliceToken = token(alice)

        val body = objectMapper.writeValueAsString(announcement("ann_1", clazz.id.toString()))
        val created = objectMapper.readValue(
            mockMvc.perform(
                post("/teacher/announcements").header("Authorization", auth(t1))
                    .contentType(MediaType.APPLICATION_JSON).content(body)
            ).andExpect(status().isOk).andReturn().response.contentAsString,
            TeacherAnnouncementPayload::class.java,
        )
        check(created.id == "ann_1")
        check(created.targetClassIds == listOf(clazz.id.toString()))
        check(created.viewCount == 0)

        // Idempotent replay of the same client id.
        mockMvc.perform(
            post("/teacher/announcements").header("Authorization", auth(t1))
                .contentType(MediaType.APPLICATION_JSON).content(body)
        ).andExpect(status().isOk)
        check(
            objectMapper.readValue(
                mockMvc.perform(get("/teacher/announcements").header("Authorization", auth(t1)))
                    .andExpect(status().isOk).andReturn().response.contentAsString,
                Array<TeacherAnnouncementPayload>::class.java,
            ).size == 1
        )

        // Fan-out reached the class student.
        val notifications = objectMapper.readValue(
            mockMvc.perform(get("/notifications").param("userId", alice.id.toString()).header("Authorization", auth(aliceToken)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            Array<AppNotificationPayload>::class.java,
        ).filter { it.type == "ANNOUNCEMENT" }
        check(notifications.size == 1)
        check(notifications.single().actionRoute == "announcements/ann_1")
        check(notifications.single().urgency == "HIGH")

        // Other staff see it as received.
        check(
            objectMapper.readValue(
                mockMvc.perform(get("/teacher/announcements/received").header("Authorization", auth(t2)))
                    .andExpect(status().isOk).andReturn().response.contentAsString,
                Array<TeacherAnnouncementPayload>::class.java,
            ).any { it.id == "ann_1" }
        )

        // A future-scheduled announcement is held: not received, no new fan-out.
        mockMvc.perform(
            post("/teacher/announcements").header("Authorization", auth(t1))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(announcement("ann_2", clazz.id.toString(), scheduledAt = System.currentTimeMillis() + 3_600_000)))
        ).andExpect(status().isOk)
        check(
            objectMapper.readValue(
                mockMvc.perform(get("/teacher/announcements/received").header("Authorization", auth(t2)))
                    .andExpect(status().isOk).andReturn().response.contentAsString,
                Array<TeacherAnnouncementPayload>::class.java,
            ).none { it.id == "ann_2" }
        )
        val afterSchedule = objectMapper.readValue(
            mockMvc.perform(get("/notifications").param("userId", alice.id.toString()).header("Authorization", auth(aliceToken)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            Array<AppNotificationPayload>::class.java,
        ).count { it.type == "ANNOUNCEMENT" }
        check(afterSchedule == 1)

        // An expired announcement is hidden from recipients.
        mockMvc.perform(
            post("/teacher/announcements").header("Authorization", auth(t1))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(announcement("ann_3", clazz.id.toString(), expiresAt = System.currentTimeMillis() - 1000)))
        ).andExpect(status().isOk)
        check(
            objectMapper.readValue(
                mockMvc.perform(get("/teacher/announcements/received").header("Authorization", auth(t2)))
                    .andExpect(status().isOk).andReturn().response.contentAsString,
                Array<TeacherAnnouncementPayload>::class.java,
            ).none { it.id == "ann_3" }
        )

        val analytics = objectMapper.readValue(
            mockMvc.perform(get("/teacher/announcements/ann_1/analytics").header("Authorization", auth(t1)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            AnnouncementAnalyticsPayload::class.java,
        )
        check(analytics.announcementId.isNotBlank() && analytics.views == 0)

        // Delete is repeat-safe and ownership-gated.
        mockMvc.perform(delete("/teacher/announcements/ann_1").header("Authorization", auth(t2)))
            .andExpect(status().isForbidden)
        mockMvc.perform(delete("/teacher/announcements/ann_1").header("Authorization", auth(t1)))
            .andExpect(status().isNoContent)
        mockMvc.perform(delete("/teacher/announcements/ann_1").header("Authorization", auth(t1)))
            .andExpect(status().isNoContent)
    }

    @Test
    fun `learner for-me returns only targeted delivered announcements`() {
        val parent = user(Role.PARENT, "Parent One", "0755000200")
        val member = user(Role.STUDENT, "Member Learner", "0755000201", parentId = parent.id, grade = "Grade 4")
        val outsider = user(Role.STUDENT, "Outsider Learner", "0755000202", grade = "Grade 5")
        val teacher = user(Role.TEACHER, "Class Teacher", "0755000203", grade = "Grade 4")
        val clazz = classRepository.save(TeacherClassEntity().apply {
            teacherUserId = teacher.id
            this.schoolId = this@AnnouncementWebTests.schoolId
            name = "Grade 4 South"
            gradeLevel = "Grade 4"
            subject = "Mathematics"
            isActive = true
        })
        membershipRepository.save(ClassMembershipEntity().apply {
            classId = clazz.id
            studentId = member.id
        })
        val t = token(teacher)
        val memberToken = token(member)
        val outsiderToken = token(outsider)
        val parentToken = token(parent)

        fun post(payload: TeacherAnnouncementPayload) {
            mockMvc.perform(post("/teacher/announcements").header("Authorization", auth(t))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk)
        }
        post(announcement("ann_cls", clazz.id.toString()))
        post(announcement("ann_grade", clazz.id.toString()).copy(
            audience = "GRADE", targetClassIds = emptyList(), targetGradeLevels = listOf(4),
        ))
        post(announcement("ann_scheduled", clazz.id.toString(), scheduledAt = System.currentTimeMillis() + 3_600_000))
        post(announcement("ann_expired", clazz.id.toString(), expiresAt = System.currentTimeMillis() - 1_000))

        val memberFeed = objectMapper.readValue(
            mockMvc.perform(get("/announcements/for-me?grade=Grade 4").header("Authorization", auth(memberToken)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            Array<TeacherAnnouncementPayload>::class.java,
        )
        check(memberFeed.any { it.id == "ann_cls" })
        check(memberFeed.any { it.id == "ann_grade" })
        check(memberFeed.none { it.id == "ann_scheduled" })
        check(memberFeed.none { it.id == "ann_expired" })
        check(memberFeed.first { it.id == "ann_cls" }.teacherName == "Class Teacher")

        val outsiderFeed = objectMapper.readValue(
            mockMvc.perform(get("/announcements/for-me?grade=Grade 5").header("Authorization", auth(outsiderToken)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            Array<TeacherAnnouncementPayload>::class.java,
        )
        check(outsiderFeed.none { it.id == "ann_cls" })
        check(outsiderFeed.none { it.id == "ann_grade" })

        val parentFeed = objectMapper.readValue(
            mockMvc.perform(get("/announcements/for-me").header("Authorization", auth(parentToken)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            Array<TeacherAnnouncementPayload>::class.java,
        )
        check(parentFeed.any { it.id == "ann_cls" })
    }
}
