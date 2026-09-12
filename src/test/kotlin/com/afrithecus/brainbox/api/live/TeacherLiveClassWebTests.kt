package com.afrithecus.brainbox.api.live

import com.afrithecus.brainbox.api.auth.web.AuthResponse
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.model.Role
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import com.afrithecus.brainbox.api.live.web.ChatMessagePayload
import com.afrithecus.brainbox.api.live.web.LiveClassAnalyticsPayload
import com.afrithecus.brainbox.api.live.web.LiveClassParticipantPayload
import com.afrithecus.brainbox.api.live.web.LiveClassPayload
import com.afrithecus.brainbox.api.live.web.LiveClassSettingsPayload
import com.afrithecus.brainbox.api.live.web.LivePollPayload
import com.afrithecus.brainbox.api.live.web.RecordedReplayPayload
import com.afrithecus.brainbox.api.live.web.TeacherLiveClassRequest
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

/** Teacher live-class hosting surface (docs/ongoing/api_live_class_changes.md). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class TeacherLiveClassWebTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val userRepository: UserRepository,
    @Autowired private val passwordEncoder: PasswordEncoder,
) {
    private fun user(role: Role, name: String, phone: String): UserEntity = userRepository.save(UserEntity().apply {
        phoneNumber = phone
        email = phone + "@live.test"
        passwordHash = passwordEncoder.encode("password123") ?: error("encode")
        this.name = name
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
    fun `host lifecycle participants chat polls and analytics`() {
        val teacher = user(Role.TEACHER, "Class Teacher", "0755040001")
        val student = user(Role.STUDENT, "Alice Learner", "0755040002")
        val other = user(Role.TEACHER, "Other Teacher", "0755040003")
        val t = token(teacher)
        val s = token(student)

        val start = System.currentTimeMillis() + 3_600_000
        val request = TeacherLiveClassRequest(
            id = "lc_1", title = "Algebra Live", subject = "Mathematics", description = "Intro",
            scheduledStart = start, scheduledEnd = start + 3_600_000,
            settings = LiveClassSettingsPayload(visibility = "CLASS_ONLY", muteOnJoin = true),
        )
        val created = objectMapper.readValue(
            mockMvc.perform(post("/teacher/live-classes?teacherId=" + teacher.id).header("Authorization", auth(t))
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            LiveClassPayload::class.java,
        )
        check(created.title == "Algebra Live")
        check(created.status == "SCHEDULED")
        check(created.teacherId == teacher.id.toString())
        val classId = created.id

        // Replay of the same client id upserts.
        mockMvc.perform(post("/teacher/live-classes?teacherId=" + teacher.id).header("Authorization", auth(t))
            .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isOk)
        val listed = objectMapper.readValue(
            mockMvc.perform(get("/teacher/live-classes?teacherId=" + teacher.id).header("Authorization", auth(t)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            Array<LiveClassPayload>::class.java,
        )
        check(listed.size == 1)

        // Another teacher cannot edit it.
        mockMvc.perform(put("/teacher/live-classes/" + classId + "").header("Authorization", auth(token(other)))
            .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isForbidden)

        // Lifecycle transitions.
        val live = objectMapper.readValue(
            mockMvc.perform(post("/teacher/live-classes/" + classId + "/start").header("Authorization", auth(t)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            LiveClassPayload::class.java,
        )
        check(live.status == "LIVE")
        val ended = objectMapper.readValue(
            mockMvc.perform(post("/teacher/live-classes/" + classId + "/end").header("Authorization", auth(t)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            LiveClassPayload::class.java,
        )
        check(ended.status == "COMPLETED")

        // A recording shows up for the teacher.
        mockMvc.perform(put("/teacher/live-classes/" + classId + "").header("Authorization", auth(t))
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(request.copy(status = "COMPLETED", recordingUrl = "https://cdn.test/rec.mp4"))))
            .andExpect(status().isOk)
        val recordings = objectMapper.readValue(
            mockMvc.perform(get("/teacher/recordings?teacherId=" + teacher.id).header("Authorization", auth(t)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            Array<RecordedReplayPayload>::class.java,
        )
        check(recordings.size == 1)
        check(recordings.single().videoUrl == "https://cdn.test/rec.mp4")

        // Participant action is idempotent and reflected in the roster.
        mockMvc.perform(post("/teacher/live-classes/" + classId + "/participants/" + student.id + "/action?action=MUTE")
            .header("Authorization", auth(t)))
            .andExpect(status().isNoContent)
        mockMvc.perform(post("/teacher/live-classes/" + classId + "/participants/" + student.id + "/action?action=MUTE")
            .header("Authorization", auth(t)))
            .andExpect(status().isNoContent)
        val roster = objectMapper.readValue(
            mockMvc.perform(get("/teacher/live-classes/" + classId + "/participants").header("Authorization", auth(t)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            Array<LiveClassParticipantPayload>::class.java,
        )
        check(roster.size == 1)
        check(roster.single().isMuted)

        // Learner chat is idempotent per message id.
        val message = ChatMessagePayload(id = "msg_1", classId = "lc_1", message = "Hello teacher")
        mockMvc.perform(post("/live/class/" + classId + "/messages").header("Authorization", auth(s))
            .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(message)))
            .andExpect(status().isOk)
        mockMvc.perform(post("/live/class/" + classId + "/messages").header("Authorization", auth(s))
            .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(message)))
            .andExpect(status().isOk)
        val transcript = objectMapper.readValue(
            mockMvc.perform(get("/teacher/live-classes/" + classId + "/messages").header("Authorization", auth(t)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            Array<ChatMessagePayload>::class.java,
        )
        check(transcript.size == 1)
        check(transcript.single().userName == "Alice Learner")

        // Host poll + learner vote flow into analytics.
        val poll = objectMapper.readValue(
            mockMvc.perform(post("/teacher/live-classes/" + classId + "/polls").header("Authorization", auth(t))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(LivePollPayload(id = "", classId = "lc_1", question = "2+2?", options = listOf("3", "4")))))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            LivePollPayload::class.java,
        )
        mockMvc.perform(post("/live/class/" + classId + "/poll/" + poll.id + "/vote?optionIndex=1").header("Authorization", auth(s)))
            .andExpect(status().isOk)

        val analytics = objectMapper.readValue(
            mockMvc.perform(get("/teacher/live-classes/" + classId + "/analytics").header("Authorization", auth(t)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            LiveClassAnalyticsPayload::class.java,
        )
        check(analytics.totalParticipants == 1)
        check(analytics.totalChatMessages == 1)
        check(analytics.totalPollResponses == 1)
        check(analytics.totalQuestionsAsked == 1)

        // Cancel is repeat-safe.
        mockMvc.perform(delete("/teacher/live-classes/" + classId + "").header("Authorization", auth(t)))
            .andExpect(status().isNoContent)
        mockMvc.perform(delete("/teacher/live-classes/" + classId + "").header("Authorization", auth(t)))
            .andExpect(status().isNoContent)
    }
}
