package com.afrithecus.brainbox.api.live

import com.afrithecus.brainbox.api.auth.web.AuthResponse
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.model.Role
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import com.afrithecus.brainbox.api.live.repository.LiveAttendanceRepository
import com.afrithecus.brainbox.api.live.web.AttendanceRequest
import com.afrithecus.brainbox.api.live.web.CreateLiveClassRequest
import com.afrithecus.brainbox.api.live.web.CreatePollRequest
import com.afrithecus.brainbox.api.live.web.LiveClassPayload
import com.afrithecus.brainbox.api.live.web.LivePollPayload
import com.afrithecus.brainbox.api.live.web.MaterialPayload
import com.afrithecus.brainbox.api.live.web.RecordedReplayPayload
import com.afrithecus.brainbox.api.live.web.TeacherSpotlightPayload
import com.afrithecus.brainbox.api.live.web.UpdateLiveClassStatusRequest
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
 * Student live classes (doc 05 §4): listing, capacity-enforced registration,
 * attendance upsert, host-only polls, single-count voting, replays and spotlight.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class LiveClassWebTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val userRepository: UserRepository,
    @Autowired private val attendanceRepository: LiveAttendanceRepository,
    @Autowired private val passwordEncoder: PasswordEncoder,
) {

    private fun auth(token: String) = "Bearer " + token

    private fun newUser(phone: String, email: String, role: Role): UserEntity =
        userRepository.save(UserEntity().apply {
            this.phoneNumber = phone
            this.email = email
            passwordHash = passwordEncoder.encode("password123") ?: error("encode")
            name = "Live " + phone
            this.role = role
            isActive = true
            isVerified = true
        })

    private fun login(email: String): String {
        val body = mockMvc.perform(
            post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("""{"identifier":"${email}","password":"password123"}""")
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return objectMapper.readValue(body, AuthResponse::class.java).sessionToken!!
    }

    private fun signup(phone: String): AuthResponse {
        val body = """{"name":"LiveStudent ${phone}","phoneNumber":"${phone}","password":"password123","role":"STUDENT"}"""
        val response = mockMvc.perform(
            post("/auth/signup").header("X-Device-Id", "dev")
                .contentType(MediaType.APPLICATION_JSON).content(body)
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return objectMapper.readValue(response, AuthResponse::class.java)
    }

    private fun createClass(adminToken: String, teacherId: String, title: String, max: Int = 100, offsetMs: Long = 3_600_000): LiveClassPayload {
        val now = System.currentTimeMillis()
        val request = CreateLiveClassRequest(
            title = title,
            subject = "Mathematics",
            teacherId = teacherId,
            scheduledStart = now + offsetMs,
            scheduledEnd = now + offsetMs + 3_600_000,
            description = "Live session",
            maxParticipants = max,
            joinUrl = "https://live.brainbox.com/" + title,
            materials = listOf(MaterialPayload("Slides", "https://cdn.brainbox.com/slides.pdf")),
        )
        val body = objectMapper.writeValueAsString(request)
        val response = mockMvc.perform(
            post("/admin/live-classes").header("Authorization", auth(adminToken))
                .contentType(MediaType.APPLICATION_JSON).content(body)
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return objectMapper.readValue(response, LiveClassPayload::class.java)
    }

    private fun setStatus(adminToken: String, classId: String, status: String, recordingUrl: String? = null): LiveClassPayload {
        val request = objectMapper.writeValueAsString(UpdateLiveClassStatusRequest(status = status, recordingUrl = recordingUrl))
        val response = mockMvc.perform(
            post("/admin/live-classes/${classId}/status").header("Authorization", auth(adminToken))
                .contentType(MediaType.APPLICATION_JSON).content(request)
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return objectMapper.readValue(response, LiveClassPayload::class.java)
    }

    @Test
    fun `list detail register and attendance`() {
        newUser("0778800000", "live.admin@test", Role.ADMIN)
        val admin = login("live.admin@test")
        val teacher = newUser("0778800001", "live.teacher@test", Role.TEACHER)
        val student = signup("0778800002")
        val other = signup("0778800003")

        val clazz = createClass(admin, teacher.id.toString(), "Quadratics Live", max = 1)
        check(clazz.status == "SCHEDULED")
        check(clazz.materials.single().name == "Slides")

        val upcomingBody = mockMvc.perform(
            get("/live/upcoming").header("Authorization", auth(student.sessionToken!!))
        ).andExpect(status().isOk).andReturn().response.contentAsString
        val upcoming = objectMapper.readValue(upcomingBody, Array<LiveClassPayload>::class.java)
        val listed = upcoming.first { it.id == clazz.id }
        check(listed.day != null && listed.time != null)
        check(listed.scheduledStartMillis == clazz.scheduledStart)

        val detailBody = mockMvc.perform(
            get("/live/class/${clazz.id}").header("Authorization", auth(student.sessionToken!!))
        ).andExpect(status().isOk).andReturn().response.contentAsString
        check(objectMapper.readValue(detailBody, LiveClassPayload::class.java).teacherName == teacher.name)

        // register succeeds and is idempotent
        mockMvc.perform(post("/live/class/${clazz.id}/register").header("Authorization", auth(student.sessionToken!!)))
            .andExpect(status().isOk)
        val again = mockMvc.perform(post("/live/class/${clazz.id}/register").header("Authorization", auth(student.sessionToken!!)))
            .andExpect(status().isOk).andReturn().response.contentAsString
        check(objectMapper.readValue(again, Map::class.java)["message"] == "Already registered")
        check(objectMapper.readValue(
            mockMvc.perform(get("/live/class/${clazz.id}").header("Authorization", auth(student.sessionToken!!)))
                .andReturn().response.contentAsString, LiveClassPayload::class.java
        ).participantCount == 1)

        // capacity of one is enforced
        mockMvc.perform(post("/live/class/${clazz.id}/register").header("Authorization", auth(other.sessionToken!!)))
            .andExpect(status().isConflict)

        // attendance upsert
        val attendance = objectMapper.writeValueAsString(AttendanceRequest(userId = student.user.id, isPresent = true, durationMinutes = 30))
        mockMvc.perform(
            post("/live/class/${clazz.id}/attendance").header("Authorization", auth(student.sessionToken!!))
                .contentType(MediaType.APPLICATION_JSON).content(attendance)
        ).andExpect(status().isOk)
        val late = objectMapper.writeValueAsString(AttendanceRequest(userId = student.user.id, status = "LATE", durationMinutes = 45))
        mockMvc.perform(
            post("/live/class/${clazz.id}/attendance").header("Authorization", auth(student.sessionToken!!))
                .contentType(MediaType.APPLICATION_JSON).content(late)
        ).andExpect(status().isOk)
        // a student cannot record attendance for someone else
        val forged = objectMapper.writeValueAsString(AttendanceRequest(userId = other.user.id, isPresent = true))
        mockMvc.perform(
            post("/live/class/${clazz.id}/attendance").header("Authorization", auth(student.sessionToken!!))
                .contentType(MediaType.APPLICATION_JSON).content(forged)
        ).andExpect(status().isForbidden)
    }

    @Test
    fun `status transitions drive now, completed and replays`() {
        newUser("0778800010", "live.admin2@test", Role.ADMIN)
        val admin = login("live.admin2@test")
        val teacher = newUser("0778800011", "live.teacher2@test", Role.TEACHER)
        val student = signup("0778800012")
        val clazz = createClass(admin, teacher.id.toString(), "Physics Live")

        setStatus(admin, clazz.id, "LIVE")
        val nowBody = mockMvc.perform(get("/live/now").header("Authorization", auth(student.sessionToken!!)))
            .andExpect(status().isOk).andReturn().response.contentAsString
        check(objectMapper.readValue(nowBody, Array<LiveClassPayload>::class.java).any { it.id == clazz.id })
        val upcoming = objectMapper.readValue(
            mockMvc.perform(get("/live/upcoming").header("Authorization", auth(student.sessionToken!!)))
                .andReturn().response.contentAsString, Array<LiveClassPayload>::class.java
        )
        check(upcoming.none { it.id == clazz.id })

        setStatus(admin, clazz.id, "COMPLETED", recordingUrl = "https://cdn.brainbox.com/rec.mp4")
        val replays = objectMapper.readValue(
            mockMvc.perform(get("/live/replays").header("Authorization", auth(student.sessionToken!!)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            Array<RecordedReplayPayload>::class.java,
        )
        val replay = replays.first { it.id == clazz.id }
        check(replay.videoUrl == "https://cdn.brainbox.com/rec.mp4")
        check(replay.views.contains("views"))
        val completed = objectMapper.readValue(
            mockMvc.perform(get("/live/completed").header("Authorization", auth(student.sessionToken!!)))
                .andReturn().response.contentAsString, Array<LiveClassPayload>::class.java
        )
        check(completed.any { it.id == clazz.id })

        val spotlight = objectMapper.readValue(
            mockMvc.perform(get("/live/spotlight").header("Authorization", auth(student.sessionToken!!)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            TeacherSpotlightPayload::class.java,
        )
        check(spotlight.name == teacher.name)
        check(spotlight.classCount >= 1)
    }

    @Test
    fun `polls are host created and votes count once`() {
        newUser("0778800020", "live.admin3@test", Role.ADMIN)
        val admin = login("live.admin3@test")
        val teacher = newUser("0778800021", "live.teacher3@test", Role.TEACHER)
        val teacherToken = login("live.teacher3@test")
        val student = signup("0778800022")
        val clazz = createClass(admin, teacher.id.toString(), "Poll Live")

        // students cannot create polls
        mockMvc.perform(
            post("/live/class/${clazz.id}/poll").header("Authorization", auth(student.sessionToken!!))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(CreatePollRequest("Ready?", listOf("Yes", "No"))))
        ).andExpect(status().isForbidden)

        val pollBody = mockMvc.perform(
            post("/live/class/${clazz.id}/poll").header("Authorization", auth(teacherToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(CreatePollRequest("Ready?", listOf("Yes", "No", "Maybe"))))
        ).andExpect(status().isOk).andReturn().response.contentAsString
        val poll = objectMapper.readValue(pollBody, LivePollPayload::class.java)
        check(poll.options.size == 3)
        check(poll.votes.values.all { it == 0 })

        val voted = mockMvc.perform(
            post("/live/class/${clazz.id}/poll/${poll.id}/vote").header("Authorization", auth(student.sessionToken!!))
                .contentType(MediaType.APPLICATION_JSON).content("""{"optionIndex":1}""")
        ).andExpect(status().isOk).andReturn().response.contentAsString
        val afterVote = objectMapper.readValue(voted, LivePollPayload::class.java)
        check(afterVote.votes[1] == 1)
        check(afterVote.results["No"] == 1)

        // re-voting moves the vote instead of double counting
        val moved = mockMvc.perform(
            post("/live/class/${clazz.id}/poll/${poll.id}/vote").header("Authorization", auth(student.sessionToken!!))
                .contentType(MediaType.APPLICATION_JSON).content("""{"optionIndex":0}""")
        ).andExpect(status().isOk).andReturn().response.contentAsString
        val afterMove = objectMapper.readValue(moved, LivePollPayload::class.java)
        check(afterMove.votes[0] == 1)
        check(afterMove.votes[1] == 0)

        mockMvc.perform(
            post("/live/class/${clazz.id}/poll/${poll.id}/vote").header("Authorization", auth(student.sessionToken!!))
                .contentType(MediaType.APPLICATION_JSON).content("""{"optionIndex":9}""")
        ).andExpect(status().isBadRequest)

        val polls = objectMapper.readValue(
            mockMvc.perform(get("/live/class/${clazz.id}/polls").header("Authorization", auth(student.sessionToken!!)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            Array<LivePollPayload>::class.java,
        )
        check(polls.single().id == poll.id)
    }

    @Test
    fun `authorization and validation`() {
        newUser("0778800030", "live.admin4@test", Role.ADMIN)
        val admin = login("live.admin4@test")
        val teacher = newUser("0778800031", "live.teacher4@test", Role.TEACHER)
        val student = signup("0778800032")

        mockMvc.perform(get("/live/upcoming")).andExpect(status().isUnauthorized)
        mockMvc.perform(
            post("/admin/live-classes").header("Authorization", auth(student.sessionToken!!))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                    CreateLiveClassRequest("X", "Maths", teacher.id.toString(), System.currentTimeMillis(), System.currentTimeMillis() + 1000)
                ))
        ).andExpect(status().isForbidden)
        mockMvc.perform(get("/live/class/${UUID.randomUUID()}").header("Authorization", auth(student.sessionToken!!)))
            .andExpect(status().isNotFound)
        mockMvc.perform(get("/live/class/not-a-uuid").header("Authorization", auth(student.sessionToken!!)))
            .andExpect(status().isBadRequest)
        mockMvc.perform(
            post("/admin/live-classes").header("Authorization", auth(admin))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                    CreateLiveClassRequest("Bad", "Maths", teacher.id.toString(), 5_000, 1_000)
                ))
        ).andExpect(status().isBadRequest)
    }

    @Test
    fun `short live sessions never count as attendance`() {
        newUser("0778800040", "live.admin5@test", Role.ADMIN)
        val admin = login("live.admin5@test")
        val teacher = newUser("0778800041", "live.teacher5@test", Role.TEACHER)
        val student = signup("0778800042")
        val clazz = createClass(admin, teacher.id.toString(), "Short Live")
        val classId = UUID.fromString(clazz.id)
        val join = System.currentTimeMillis() - 10 * 60_000

        fun record(request: AttendanceRequest) =
            mockMvc.perform(
                post("/live/class/" + clazz.id + "/attendance").header("Authorization", auth(student.sessionToken!!))
                    .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(request))
            ).andExpect(status().isOk)

        // Offline replay sends only timestamps; a 2 minute session is absent despite no isPresent flag.
        record(AttendanceRequest(userId = student.user.id, checkInTime = join, leaveTime = join + 2 * 60_000))
        val short = requireNotNull(attendanceRepository.findByClassIdAndStudentId(classId, UUID.fromString(student.user.id)))
        check(short.durationMinutes == 2)
        check(short.status == "ABSENT")
        check(short.isPresent.not())
        check(short.leftAt != null)

        // At the five minute threshold the session counts.
        record(AttendanceRequest(userId = student.user.id, checkInTime = join, leaveTime = join + 5 * 60_000))
        val threshold = requireNotNull(attendanceRepository.findByClassIdAndStudentId(classId, UUID.fromString(student.user.id)))
        check(threshold.durationMinutes == 5)
        check(threshold.status == "PRESENT")
        check(threshold.isPresent)

        // Still in the room (no leave time) reads present.
        record(AttendanceRequest(userId = student.user.id, checkInTime = join))
        val inProgress = requireNotNull(attendanceRepository.findByClassIdAndStudentId(classId, UUID.fromString(student.user.id)))
        check(inProgress.status == "PRESENT")
        check(inProgress.isPresent)
    }
}
