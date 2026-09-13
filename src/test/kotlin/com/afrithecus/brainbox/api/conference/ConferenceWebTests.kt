package com.afrithecus.brainbox.api.conference

import com.afrithecus.brainbox.api.auth.web.AuthResponse
import com.afrithecus.brainbox.api.classes.entity.ClassMembershipEntity
import com.afrithecus.brainbox.api.classes.entity.TeacherClassEntity
import com.afrithecus.brainbox.api.classes.repository.ClassMembershipRepository
import com.afrithecus.brainbox.api.classes.repository.TeacherClassRepository
import com.afrithecus.brainbox.api.conference.web.ConferenceBookingPayload
import com.afrithecus.brainbox.api.conference.web.ConferenceSlotPayload
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.model.Role
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import com.afrithecus.brainbox.api.notification.web.AppNotificationPayload
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.util.UUID

/** Teacher/parent conferences (doc 04 section 15, api_conference_changes.md). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ConferenceWebTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val userRepository: UserRepository,
    @Autowired private val classRepository: TeacherClassRepository,
    @Autowired private val membershipRepository: ClassMembershipRepository,
    @Autowired private val passwordEncoder: PasswordEncoder,
) {
    private fun user(role: Role, name: String, phone: String, parentId: UUID? = null): UserEntity =
        userRepository.save(UserEntity().apply {
            phoneNumber = phone
            email = phone + "@conference.test"
            passwordHash = passwordEncoder.encode("password123") ?: error("encode")
            this.name = name
            this.role = role
            this.parentUserId = parentId
            gradeLevel = if (role == Role.STUDENT) "Grade 4" else null
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
    fun `teacher slots, parent booking, reminder, meet link and cancel`() {
        val teacher = user(Role.TEACHER, "Class Teacher", "0755120001")
        val other = user(Role.TEACHER, "Other Teacher", "0755120002")
        val parent = user(Role.PARENT, "Parent One", "0755120003")
        val child = user(Role.STUDENT, "Alice Learner", "0755120004", parentId = parent.id)
        val clazz = classRepository.save(TeacherClassEntity().apply {
            teacherUserId = teacher.id
            name = "Grade 4 South"
            gradeLevel = "Grade 4"
            subject = "Mathematics"
            isActive = true
        })
        membershipRepository.save(ClassMembershipEntity().apply {
            classId = clazz.id
            studentId = child.id
        })
        val t = token(teacher)
        val p = token(parent)

        val slot = ConferenceSlotPayload(
            id = "local_1",
            title = "Parent-Teacher Conference",
            date = System.currentTimeMillis() + 86_400_000L,
            startTime = "10:00",
            endTime = "10:30",
            durationMinutes = 30,
            maxBookings = 1,
            audienceTarget = "WHOLE_SCHOOL",
        )
        val created = objectMapper.readValue(
            mockMvc.perform(post("/teacher/conference/slot").header("Authorization", auth(t))
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(slot)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            ConferenceSlotPayload::class.java,
        )
        check(created.id.isNotBlank())
        check(created.id != "local_1")
        check(created.status == "OPEN")
        check(created.teacherName == "Class Teacher")

        // Replay the same client id: no duplicate.
        mockMvc.perform(post("/teacher/conference/slot").header("Authorization", auth(t))
            .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(slot)))
            .andExpect(status().isOk)
        val slots = objectMapper.readValue(
            mockMvc.perform(get("/teacher/conference/" + teacher.id + "/slots").header("Authorization", auth(t)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            Array<ConferenceSlotPayload>::class.java,
        )
        check(slots.size == 1)

        val updated = objectMapper.readValue(
            mockMvc.perform(put("/teacher/conference/slot").header("Authorization", auth(t))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(slot.copy(id = created.id, title = "Term Conference"))))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            ConferenceSlotPayload::class.java,
        )
        check(updated.title == "Term Conference")

        val parentSlots = objectMapper.readValue(
            mockMvc.perform(get("/parent/conference/slots").header("Authorization", auth(p)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            Array<ConferenceSlotPayload>::class.java,
        )
        check(parentSlots.size == 1)
        check(parentSlots.single().isBooked.not())

        val booking = ConferenceBookingPayload(
            id = "b_local_1", slotId = created.id, childId = child.id.toString(),
            notes = "Needs support with algebra",
        )
        val saved = objectMapper.readValue(
            mockMvc.perform(post("/parent/conference/book").header("Authorization", auth(p))
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(booking)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            ConferenceBookingPayload::class.java,
        )
        check(saved.teacherName == "Class Teacher")
        check(saved.parentId == parent.id.toString())
        check(saved.status == "PENDING")
        check(saved.requestedAt != null)

        mockMvc.perform(post("/parent/conference/book").header("Authorization", auth(p))
            .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(booking)))
            .andExpect(status().isOk)

        // A pending request soft-holds the only seat of a maxBookings == 1 slot.
        val heldSlots = objectMapper.readValue(
            mockMvc.perform(get("/parent/conference/slots").header("Authorization", auth(p)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            Array<ConferenceSlotPayload>::class.java,
        )
        check(heldSlots.single().isBooked)
        check(heldSlots.single().status == "FULL")
        val parentBookings = objectMapper.readValue(
            mockMvc.perform(get("/parent/conference/bookings").header("Authorization", auth(p)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            Array<ConferenceBookingPayload>::class.java,
        )
        check(parentBookings.size == 1)

        val teacherBookings = objectMapper.readValue(
            mockMvc.perform(get("/teacher/conference/slot/" + created.id + "/bookings").header("Authorization", auth(t)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            Array<ConferenceBookingPayload>::class.java,
        )
        check(teacherBookings.size == 1)
        check(teacherBookings.single().childName == "Alice Learner")
        check(teacherBookings.single().childGrade == "Grade 4")
        check(teacherBookings.single().status == "PENDING")

        val confirmed = objectMapper.readValue(
            mockMvc.perform(patch("/teacher/conference/booking/" + saved.id + "/status").header("Authorization", auth(t))
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"CONFIRMED\"}"))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            ConferenceBookingPayload::class.java,
        )
        check(confirmed.status == "CONFIRMED")
        check(confirmed.confirmedAt != null)
        // Replaying the decision is idempotent.
        mockMvc.perform(patch("/teacher/conference/booking/" + saved.id + "/status").header("Authorization", auth(t))
            .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"CONFIRMED\"}"))
            .andExpect(status().isOk)

        // Reminder (JSON string body) notifies the parent once per window.
        mockMvc.perform(post("/teacher/conference/booking/" + saved.id + "/reminder").header("Authorization", auth(t))
            .contentType(MediaType.APPLICATION_JSON).content("\"EMAIL\""))
            .andExpect(status().isNoContent)
        val notifications = objectMapper.readValue(
            mockMvc.perform(get("/notifications").param("userId", parent.id.toString()).header("Authorization", auth(p)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            Array<AppNotificationPayload>::class.java,
        )
        check(notifications.any { it.title == "Conference reminder" })

        // Stable meet link.
        val link1 = mockMvc.perform(get("/teacher/conference/slot/" + created.id + "/meet-link")
            .header("Authorization", auth(t)))
            .andExpect(status().isOk).andReturn().response.contentAsString
        val link2 = mockMvc.perform(get("/teacher/conference/slot/" + created.id + "/meet-link")
            .header("Authorization", auth(t)))
            .andExpect(status().isOk).andReturn().response.contentAsString
        check(link1.isNotBlank() && link1 == link2)

        // Another teacher is scoped out.
        mockMvc.perform(delete("/teacher/conference/slot/" + created.id).header("Authorization", auth(token(other))))
            .andExpect(status().isForbidden)

        // Parent cancel is repeat-safe and frees the slot.
        mockMvc.perform(delete("/parent/conference/booking/" + saved.id).header("Authorization", auth(p)))
            .andExpect(status().isNoContent)
        mockMvc.perform(delete("/parent/conference/booking/" + saved.id).header("Authorization", auth(p)))
            .andExpect(status().isNoContent)
        check(
            objectMapper.readValue(
                mockMvc.perform(get("/parent/conference/bookings").header("Authorization", auth(p)))
                    .andExpect(status().isOk).andReturn().response.contentAsString,
                Array<ConferenceBookingPayload>::class.java,
            ).isEmpty()
        )
    }
}
