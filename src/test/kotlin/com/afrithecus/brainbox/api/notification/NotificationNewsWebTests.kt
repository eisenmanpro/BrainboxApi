package com.afrithecus.brainbox.api.notification

import com.afrithecus.brainbox.api.auth.web.AuthResponse
import com.afrithecus.brainbox.api.classes.entity.ClassMembershipEntity
import com.afrithecus.brainbox.api.classes.entity.TeacherClassEntity
import com.afrithecus.brainbox.api.classes.repository.ClassMembershipRepository
import com.afrithecus.brainbox.api.classes.repository.TeacherClassRepository
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.model.Role
import com.afrithecus.brainbox.api.identity.model.SubscriptionStatus
import com.afrithecus.brainbox.api.identity.model.SubscriptionTier
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import com.afrithecus.brainbox.api.notification.web.AppNotificationPayload
import com.afrithecus.brainbox.api.notification.web.CreateNewsRequest
import com.afrithecus.brainbox.api.notification.web.CreateNotificationRequest
import com.afrithecus.brainbox.api.notification.web.NewsItemPayload
import com.afrithecus.brainbox.api.subscription.entity.SubscriptionEntity
import com.afrithecus.brainbox.api.subscription.repository.SubscriptionRepository
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
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Notifications + news (doc 05 §5/§6) and the subscription-lifecycle reminders:
 * student/parent messaging by plan/expiry and teacher nudges to remind guardians.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class NotificationNewsWebTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val userRepository: UserRepository,
    @Autowired private val subscriptionRepository: SubscriptionRepository,
    @Autowired private val teacherClassRepository: TeacherClassRepository,
    @Autowired private val membershipRepository: ClassMembershipRepository,
    @Autowired private val passwordEncoder: PasswordEncoder,
    @Autowired private val clock: Clock,
) {

    private fun auth(token: String) = "Bearer " + token

    private fun newUser(phone: String, email: String, role: Role): UserEntity =
        userRepository.save(UserEntity().apply {
            this.phoneNumber = phone
            this.email = email
            passwordHash = passwordEncoder.encode("password123") ?: error("encode")
            name = "Notify " + phone
            this.role = role
            isActive = true
            isVerified = true
        })

    private fun signup(phone: String): AuthResponse {
        val body = """{"name":"NotifyStudent ${phone}","phoneNumber":"${phone}","password":"password123","role":"STUDENT"}"""
        val response = mockMvc.perform(
            post("/auth/signup").header("X-Device-Id", "dev")
                .contentType(MediaType.APPLICATION_JSON).content(body)
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return objectMapper.readValue(response, AuthResponse::class.java)
    }

    private fun login(email: String): String {
        val body = mockMvc.perform(
            post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("""{"identifier":"${email}","password":"password123"}""")
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return objectMapper.readValue(body, AuthResponse::class.java).sessionToken!!
    }

    private fun notifications(token: String, userId: String, extra: String = ""): List<AppNotificationPayload> {
        val body = mockMvc.perform(
            get("/notifications?userId=${userId}${extra}").header("Authorization", auth(token))
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return objectMapper.readValue(body, Array<AppNotificationPayload>::class.java).toList()
    }

    private fun setSubscription(userId: UUID, tier: SubscriptionTier, status: SubscriptionStatus, expiry: Instant?) {
        // signup already provisions a subscription row, so update it in place
        val row = subscriptionRepository.findByUserId(userId) ?: SubscriptionEntity().apply { this.userId = userId }
        row.tier = tier
        row.status = status
        row.expiryDate = expiry
        subscriptionRepository.save(row)
    }

    @Test
    fun `notification lifecycle with ownership`() {
        val admin = newUser("0779000000", "notify.admin@test", Role.ADMIN)
        val adminToken = login("notify.admin@test")
        val student = signup("0779000001")
        val other = signup("0779000002")

        // admin (no system reminders) creates a notification for themselves
        val created = objectMapper.readValue(
            mockMvc.perform(
                post("/notifications?userId=${admin.id}").header("Authorization", auth(adminToken))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(CreateNotificationRequest("Heads up", "System maintenance tonight", type = "ANNOUNCEMENT", urgency = "HIGH")))
            ).andExpect(status().isOk).andReturn().response.contentAsString,
            AppNotificationPayload::class.java,
        )
        check(created.isRead.not())
        check(created.urgency == "HIGH")

        // students cannot push notifications to someone else
        mockMvc.perform(
            post("/notifications?userId=${other.user.id}").header("Authorization", auth(student.sessionToken!!))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(CreateNotificationRequest("Hi", "not allowed")))
        ).andExpect(status().isForbidden)

        // another user cannot mark it read
        mockMvc.perform(
            post("/notifications/${created.id}/read").header("Authorization", auth(student.sessionToken!!))
        ).andExpect(status().isNotFound)
        mockMvc.perform(
            post("/notifications/${created.id}/read").header("Authorization", auth(adminToken))
        ).andExpect(status().isNoContent)
        check(notifications(adminToken, admin.id.toString()).first { it.id == created.id }.isRead)

        // archive hides it from the default list
        mockMvc.perform(
            post("/notifications/${created.id}/archive").header("Authorization", auth(adminToken))
        ).andExpect(status().isNoContent)
        check(notifications(adminToken, admin.id.toString()).none { it.id == created.id })
        check(notifications(adminToken, admin.id.toString(), "&includeArchived=true").any { it.id == created.id })

        // delete all clears the mailbox
        mockMvc.perform(delete("/notifications?userId=${admin.id}").header("Authorization", auth(adminToken)))
            .andExpect(status().isNoContent)
        check(notifications(adminToken, admin.id.toString(), "&includeArchived=true").isEmpty())
    }

    @Test
    fun `student subscription reminders follow plan and expiry`() {
        // free plan -> upgrade prompt
        val free = signup("0779000010")
        val freeRows = notifications(free.sessionToken!!, free.user.id)
        val freeNotice = freeRows.first { it.type == "SYSTEM" }
        check(freeNotice.title == "Unlock full learning")
        check(freeNotice.actionRoute == "subscription")

        // active plan nearing expiry -> renewal reminder
        val expiring = signup("0779000011")
        setSubscription(UUID.fromString(expiring.user.id), SubscriptionTier.EXPLORER, SubscriptionStatus.ACTIVE, clock.instant().plus(5, ChronoUnit.DAYS))
        val expiringNotice = notifications(expiring.sessionToken!!, expiring.user.id).first { it.type == "SYSTEM" }
        check(expiringNotice.title.contains("expires in"))
        check(expiringNotice.urgency == "HIGH")
        check(expiringNotice.metadata["plan"] == "EXPLORER")

        // healthy active plan -> informational
        val healthy = signup("0779000012")
        setSubscription(UUID.fromString(healthy.user.id), SubscriptionTier.PRO, SubscriptionStatus.ACTIVE, clock.instant().plus(60, ChronoUnit.DAYS))
        val healthyNotice = notifications(healthy.sessionToken!!, healthy.user.id).first { it.type == "SYSTEM" }
        check(healthyNotice.title.contains("PRO"))
        check(healthyNotice.urgency == "LOW")

        // expired plan -> urgent renewal
        val expired = signup("0779000013")
        setSubscription(UUID.fromString(expired.user.id), SubscriptionTier.EXPLORER, SubscriptionStatus.EXPIRED, clock.instant().minus(2, ChronoUnit.DAYS))
        val expiredNotice = notifications(expired.sessionToken!!, expired.user.id).first { it.type == "SYSTEM" }
        check(expiredNotice.title == "Subscription expired")
        check(expiredNotice.urgency == "URGENT")

        // reminders are deduplicated across reads
        val repeated = notifications(expiring.sessionToken!!, expiring.user.id)
        check(repeated.count { it.type == "SYSTEM" } == 1)
    }

    @Test
    fun `parent gets child reminders and teacher gets guardian nudges`() {
        val parent = newUser("0779000020", "notify.parent@test", Role.PARENT)
        val parentToken = login("notify.parent@test")
        val child = signup("0779000021")
        val childUser = userRepository.findById(UUID.fromString(child.user.id)).orElseThrow().apply { parentUserId = parent.id }
        userRepository.save(childUser)
        setSubscription(parent.id, SubscriptionTier.BASE, SubscriptionStatus.NONE, null)

        val parentNotice = notifications(parentToken, parent.id.toString()).first { it.type == "SYSTEM" }
        check(parentNotice.message.contains(childUser.name))
        check(parentNotice.actionRoute == "subscription")

        // teacher with a learner whose plan is expiring is nudged to remind guardians
        val teacher = newUser("0779000022", "notify.teacher@test", Role.TEACHER)
        val teacherToken = login("notify.teacher@test")
        val clazz = teacherClassRepository.save(TeacherClassEntity().apply {
            teacherUserId = teacher.id
            name = "Form 3 North"
            gradeLevel = "Form 3"
            subject = "Mathematics"
        })
        membershipRepository.save(ClassMembershipEntity().apply {
            classId = clazz.id
            studentId = childUser.id
        })

        val teacherNotice = notifications(teacherToken, teacher.id.toString()).first { it.type == "SYSTEM" }
        check(teacherNotice.title.contains("Remind"))
        check(teacherNotice.actionRoute == "teacher_dashboard")
        check(teacherNotice.metadata["studentsNeedingRenewal"] == "1")

        // a learner with a healthy plan produces no nudge
        val healthyStudent = signup("0779000023")
        setSubscription(UUID.fromString(healthyStudent.user.id), SubscriptionTier.PRO, SubscriptionStatus.ACTIVE, clock.instant().plus(90, ChronoUnit.DAYS))
        membershipRepository.deleteByClassIdAndStudentId(clazz.id, childUser.id)
        membershipRepository.save(ClassMembershipEntity().apply {
            classId = clazz.id
            studentId = UUID.fromString(healthyStudent.user.id)
        })
        check(notifications(teacherToken, teacher.id.toString()).none { it.type == "SYSTEM" })
    }

    @Test
    fun `news publishing and feed`() {
        val admin = newUser("0779000030", "notify.admin2@test", Role.ADMIN)
        val adminToken = login("notify.admin2@test")
        val student = signup("0779000031")

        val published = objectMapper.readValue(
            mockMvc.perform(
                post("/admin/news").header("Authorization", auth(adminToken))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(CreateNewsRequest("STEM Scholarships Open", "Applications are now open.", imageUrl = "https://cdn.brainbox.com/n1.jpg", category = "Scholarships", tags = listOf("stem", "funding"))))
            ).andExpect(status().isOk).andReturn().response.contentAsString,
            NewsItemPayload::class.java,
        )
        check(published.status == "PUBLISHED")
        check(published.tags.contains("stem"))

        // a draft stays out of the public feed
        mockMvc.perform(
            post("/admin/news").header("Authorization", auth(adminToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(CreateNewsRequest("Draft Story", "Not ready", status = "DRAFT")))
        ).andExpect(status().isOk)

        val feed = objectMapper.readValue(
            mockMvc.perform(get("/news").header("Authorization", auth(student.sessionToken!!)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            Array<NewsItemPayload>::class.java,
        )
        check(feed.any { it.id == published.id })
        check(feed.none { it.title == "Draft Story" })

        val filtered = objectMapper.readValue(
            mockMvc.perform(get("/news?category=Scholarships").header("Authorization", auth(student.sessionToken!!)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            Array<NewsItemPayload>::class.java,
        )
        check(filtered.any { it.id == published.id })

        val detail = objectMapper.readValue(
            mockMvc.perform(get("/news/${published.id}").header("Authorization", auth(student.sessionToken!!)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            NewsItemPayload::class.java,
        )
        check(detail.content == "Applications are now open.")

        mockMvc.perform(get("/news")).andExpect(status().isUnauthorized)
        mockMvc.perform(
            post("/admin/news").header("Authorization", auth(student.sessionToken!!))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(CreateNewsRequest("Nope", "not allowed")))
        ).andExpect(status().isForbidden)
        mockMvc.perform(
            post("/admin/news").header("Authorization", auth(adminToken))
                .contentType(MediaType.APPLICATION_JSON).content("""{"title":"","content":"x"}""")
        ).andExpect(status().isBadRequest)
        mockMvc.perform(get("/news/not-a-uuid").header("Authorization", auth(student.sessionToken!!)))
            .andExpect(status().isBadRequest)
    }
}
