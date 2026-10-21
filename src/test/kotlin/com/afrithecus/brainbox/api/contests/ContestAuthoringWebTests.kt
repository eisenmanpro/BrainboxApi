package com.afrithecus.brainbox.api.contests

import com.afrithecus.brainbox.api.auth.web.AuthResponse
import com.afrithecus.brainbox.api.contests.web.ContestPayload
import com.afrithecus.brainbox.api.contests.web.CreateContestQuestionRequest
import com.afrithecus.brainbox.api.contests.web.CreateContestRequest
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.entity.SchoolEntity
import com.afrithecus.brainbox.api.identity.model.Role
import com.afrithecus.brainbox.api.identity.repository.SchoolRepository
import com.afrithecus.brainbox.api.identity.repository.UserRepository
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
import java.util.concurrent.atomic.AtomicInteger

/**
 * F4 teacher authoring + grade-level visibility:
 * a SCHOOL_GRADE contest is visible only to learners of that grade in the author's school, teachers
 * author drafts and publish/finalise them, and another school cannot see it.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ContestAuthoringWebTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val userRepository: UserRepository,
    @Autowired private val schoolRepository: SchoolRepository,
    @Autowired private val passwordEncoder: PasswordEncoder,
) {

    private val counter = AtomicInteger(0)

    private fun newSchool(name: String): UUID {
        val school = SchoolEntity().apply {
            this.name = name
        }
        schoolRepository.save(school)
        return school.id
    }

    private fun user(role: Role, grade: String? = null, school: UUID? = null): AuthResponse {
        val n = counter.incrementAndGet()
        val email = "contest" + role.name.lowercase() + n + "@f4.test"
        val user = UserEntity().apply {
            phoneNumber = "0755" + (1000 + n)
            this.email = email
            passwordHash = passwordEncoder.encode("password123") ?: error("encode")
            name = role.name + " " + n
            this.role = role
            this.schoolId = school
            this.gradeLevel = grade
            isVerified = true
            isActive = true
        }
        userRepository.save(user)
        return login(email)
    }

    private fun login(identifier: String): AuthResponse {
        val response = mockMvc.perform(
            post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"identifier\":\"" + identifier + "\",\"password\":\"password123\"}")
        ).andReturn()
        check(response.response.status == 200) { response.response.contentAsString }
        return objectMapper.readValue(response.response.contentAsString, AuthResponse::class.java)
    }

    private fun auth(response: AuthResponse) = "Bearer " + response.sessionToken

    private fun draft(title: String, grade: String): CreateContestRequest {
        val now = Instant.now()
        return CreateContestRequest(
            title = title,
            subject = "Science & Technology",
            grade = grade,
            startTime = now.plusSeconds(3600).toEpochMilli(),
            endTime = now.plusSeconds(7200).toEpochMilli(),
            prize = null,
            questions = listOf(
                CreateContestQuestionRequest(
                    text = "Which gas do plants absorb?",
                    type = "MCQ",
                    options = listOf("Oxygen", "Carbon dioxide"),
                    correctAnswer = "Carbon dioxide",
                    points = 2,
                )
            ),
        )
    }

    private fun createTeacherContest(teacher: AuthResponse, title: String, grade: String): ContestPayload {
        val response = mockMvc.perform(
            post("/teacher/contests").header("Authorization", auth(teacher))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(draft(title, grade)))
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return objectMapper.readValue(response, ContestPayload::class.java)
    }

    private fun upcoming(student: AuthResponse): List<ContestPayload> {
        val body = mockMvc.perform(
            get("/contests/upcoming").header("Authorization", auth(student))
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return objectMapper.readValue(body, Array<ContestPayload>::class.java).toList()
    }

    @Test
    fun teacherDraftPublishesAndIsVisibleOnlyToThatGradeInThatSchool() {
        val schoolA = newSchool("F4 School A")
        val schoolB = newSchool("F4 School B")
        val teacher = user(Role.TEACHER, school = schoolA)
        val grade7 = user(Role.STUDENT, "Grade 7", schoolA)
        val grade8 = user(Role.STUDENT, "Grade 8", schoolA)
        val anotherSchool = user(Role.STUDENT, "Grade 7", schoolB)

        val draft = createTeacherContest(teacher, "Grade 7 Science", "Grade 7")
        check(draft.lifecycle == "DRAFT")
        check(draft.scope == "SCHOOL_GRADE")
        check(draft.schoolId == schoolA.toString())
        check(draft.questionCount == 1)
        check(draft.totalPoints == 2)

        // A draft is not visible to learners.
        check(upcoming(grade7).none { it.id == draft.id })

        mockMvc.perform(
            post("/teacher/contests/" + draft.id + "/publish").header("Authorization", auth(teacher))
        ).andExpect(status().isOk)

        check(upcoming(grade7).any { it.id == draft.id })
        check(upcoming(grade8).none { it.id == draft.id })
        check(upcoming(anotherSchool).none { it.id == draft.id })

        // Detail outside the grade/school must 404 rather than leak existence.
        mockMvc.perform(
            get("/contests/" + draft.id).header("Authorization", auth(grade8))
        ).andExpect(status().isNotFound)

        // Finalise closes the window and marks the contest completed/finalised.
        val finalised = mockMvc.perform(
            post("/teacher/contests/" + draft.id + "/finalise").header("Authorization", auth(teacher))
        ).andExpect(status().isOk).andReturn().response.contentAsString
        val payload = objectMapper.readValue(finalised, ContestPayload::class.java)
        check(payload.lifecycle == "PUBLISHED")
        check(payload.finalised)
        check(payload.status == "COMPLETED")
    }

    @Test
    fun anotherTeacherCannotEditOrCancelThisTeachersContest() {
        val school = newSchool("F4 School C")
        val owner = user(Role.TEACHER, school = school)
        val other = user(Role.TEACHER, school = school)
        val draft = createTeacherContest(owner, "Owned draft", "Grade 9")

        mockMvc.perform(
            post("/teacher/contests/" + draft.id + "/publish").header("Authorization", auth(other))
        ).andExpect(status().isForbidden)

        mockMvc.perform(
            get("/teacher/contests").header("Authorization", auth(other))
        ).andExpect(status().isOk)
        val listed = objectMapper.readValue(
            mockMvc.perform(get("/teacher/contests").header("Authorization", auth(other)))
                .andReturn().response.contentAsString,
            Array<ContestPayload>::class.java,
        ).toList()
        check(listed.none { it.id == draft.id })
    }
}
