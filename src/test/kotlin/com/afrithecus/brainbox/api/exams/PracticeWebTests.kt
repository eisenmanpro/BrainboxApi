package com.afrithecus.brainbox.api.exams

import com.afrithecus.brainbox.api.auth.web.AuthResponse
import com.afrithecus.brainbox.api.exams.repository.ExamQuestionRepository
import com.afrithecus.brainbox.api.exams.repository.ExamRepository
import com.afrithecus.brainbox.api.exams.web.CreateExamQuestionRequest
import com.afrithecus.brainbox.api.exams.web.CreateExamRequest
import com.afrithecus.brainbox.api.exams.web.ExamCard
import com.afrithecus.brainbox.api.exams.web.ExamDetail
import com.afrithecus.brainbox.api.exams.web.PracticePaperPayload
import com.afrithecus.brainbox.api.identity.entity.SchoolEntity
import com.afrithecus.brainbox.api.identity.entity.UserEntity
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
import java.util.UUID

/**
 * Personalised practice (§B7). A generated paper is cloned from reviewed practice content,
 * scoped to the one learner, readable by that learner, a teacher in their school and their
 * guardian — and invisible everywhere else: not in the catalog, not to another learner,
 * and not through the practice-paper content or attempt routes.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class PracticeWebTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val userRepository: UserRepository,
    @Autowired private val passwordEncoder: PasswordEncoder,
    @Autowired private val examRepository: ExamRepository,
    @Autowired private val questionRepository: ExamQuestionRepository,
    @Autowired private val schools: SchoolRepository,
) {

    private fun auth(token: String) = "Bearer " + token

    private fun school(): SchoolEntity = schools.save(
        SchoolEntity().apply {
            name = "Practice School " + UUID.randomUUID()
            county = "Nairobi"
        }
    )

    private fun account(
        email: String,
        phone: String,
        role: Role,
        schoolId: UUID? = null,
        gradeLevel: String? = null,
    ): UserEntity = userRepository.save(
        UserEntity().apply {
            phoneNumber = phone
            this.email = email
            passwordHash = passwordEncoder.encode("password123") ?: error("encode")
            name = role.name + " " + phone
            this.role = role
            isActive = true
            isVerified = true
            this.schoolId = schoolId
            this.gradeLevel = gradeLevel
        }
    )

    /** Login is by phone number; the response carries a session token. */
    private fun token(user: UserEntity): String {
        val response = mockMvc.perform(
            post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("""{"identifier":"${user.phoneNumber}","password":"password123"}""")
        ).andReturn().response
        check(response.status == 200) { "login failed: " + response.status + " " + response.contentAsString }
        return objectMapper.readValue(response.contentAsString, AuthResponse::class.java).sessionToken!!
    }

    /** A published practice paper authored by an admin, i.e. shared catalog content. */
    private fun seedReviewedPaper(adminToken: String, subject: String = "Mathematics", questions: Int = 6): String {
        val items = (1..questions).map { index ->
            CreateExamQuestionRequest(
                text = "Reviewed question $index",
                type = "MCQ",
                options = listOf("A", "B", "C"),
                correctAnswer = "A",
                explanation = "explanation $index",
                points = 2,
                difficulty = ((index - 1) % 5) + 1,
                topic = "Fractions",
            )
        }
        val request = CreateExamRequest(
            title = "Reviewed $subject Practice",
            subject = subject,
            examType = "PRACTICE_PAPER",
            durationMinutes = 60,
            examYear = 2024,
            isPublished = true,
            questions = items,
        )
        val raw = mockMvc.perform(
            post("/admin/exams").header("Authorization", auth(adminToken))
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(request))
        ).andExpect(status().isOk).andReturn().response.contentAsString
        val paperId = objectMapper.readValue(raw, ExamDetail::class.java).id
        // admin-authored exams carry no grade, so give it one the learner can match
        val exam = examRepository.findById(UUID.fromString(paperId)).orElseThrow()
        exam.gradeLevel = 6
        examRepository.save(exam)
        return paperId
    }

    private fun generate(token: String, body: String): PracticePaperPayload {
        val response = mockMvc.perform(
            post("/practice/generate").header("Authorization", auth(token))
                .contentType(MediaType.APPLICATION_JSON).content(body)
        ).andReturn().response
        check(response.status == 200) { "generate failed: " + response.status + " " + response.contentAsString }
        return objectMapper.readValue(response.contentAsString, PracticePaperPayload::class.java)
    }

    private fun recordAttempt(token: String, examId: String, score: Int, totalPoints: Int, percentage: Int) {
        mockMvc.perform(
            post("/practice-papers/$examId/attempts").header("Authorization", auth(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"score":$score,"totalPoints":$totalPoints,"percentage":$percentage}""")
        ).andExpect(status().isNoContent)
    }

    private fun history(token: String): List<PracticePaperPayload> {
        val raw = mockMvc.perform(get("/practice/history").header("Authorization", auth(token)))
            .andExpect(status().isOk).andReturn().response.contentAsString
        return objectMapper.readValue(
            raw,
            objectMapper.typeFactory.constructCollectionType(List::class.java, PracticePaperPayload::class.java),
        )
    }

    private fun cards(token: String): List<ExamCard> {
        val raw = mockMvc.perform(get("/exams/hub/all").header("Authorization", auth(token)))
            .andExpect(status().isOk).andReturn().response.contentAsString
        return objectMapper.readValue(
            raw,
            objectMapper.typeFactory.constructCollectionType(List::class.java, ExamCard::class.java),
        )
    }

    private fun adminToken(): String =
        token(account("practice.admin@test", "0779410000", Role.ADMIN))

    @Test
    fun `a learner gets a private paper cloned from reviewed content`() {
        val admin = adminToken()
        val reviewedId = seedReviewedPaper(admin)
        val learner = account("practice.learner@test", "0779410001", Role.STUDENT, school().id, "Grade 6")
        val learnerToken = token(learner)

        val paper = generate(learnerToken, """{"subject":"Mathematics","questionCount":3}""")

        check(paper.examId != reviewedId)
        check(paper.subject == "Mathematics")
        check(paper.questionCount == 3)
        check(paper.topic == "Fractions")
        check(paper.totalPoints == 6)
        check(paper.difficulty in 1..5)
        check(paper.durationMinutes == 15)
        check(paper.attempts == 0)
        check(paper.bestPercentage == null)
        check(paper.createdAt > 0)

        // a real exam row, owned by the learner and published so sessions work
        val exam = examRepository.findById(UUID.fromString(paper.examId)).orElseThrow()
        check(exam.status.name == "PUBLISHED")
        check(exam.scope.name == "PERSONAL")
        check(exam.ownerUserId == learner.id)
        check(exam.createdBy == learner.id)
        check(!exam.clientId.isNullOrEmpty())

        // questions cloned in order, each with its own client id and the reviewed key
        val cloned = questionRepository.findAllByExamIdOrderByOrderIndexAsc(UUID.fromString(paper.examId))
        check(cloned.size == 3)
        check(cloned.map { it.orderIndex } == listOf(0, 1, 2))
        check(cloned.all { it.examId == exam.id })
        check(cloned.all { it.correctAnswer == "A" })
        check(cloned.all { it.clientId == exam.id.toString() + "_q" + (it.orderIndex + 1) })
        check(cloned.map { it.text }.toSet().size == 3)

        // the learner's own history sees it
        check(history(learnerToken).map { it.examId } == listOf(paper.examId))

        // the shared catalog does not list it
        check(cards(learnerToken).none { it.id == paper.examId })

        // the paper is playable: keys come through the practice-paper content route and a
        // self-graded attempt is recorded against it
        val content = mockMvc.perform(
            get("/practice-papers/${paper.examId}/content").header("Authorization", auth(learnerToken))
        ).andExpect(status().isOk).andReturn().response.contentAsString
        check(content.contains(paper.examId))
        check(content.contains("markingScheme"))
        mockMvc.perform(
            post("/practice-papers/${paper.examId}/attempts").header("Authorization", auth(learnerToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"score":6,"totalPoints":6,"percentage":100}""")
        ).andExpect(status().isNoContent)
        val rescored = history(learnerToken).single()
        check(rescored.attempts == 1)
        check(rescored.bestPercentage == 100)

        // and it also serves the online session route
        mockMvc.perform(
            get("/exams/${paper.examId}/session").header("Authorization", auth(learnerToken))
        ).andExpect(status().isOk)
    }

    @Test
    fun `the paper is scoped to one learner`() {
        val admin = adminToken()
        seedReviewedPaper(admin)
        val cohort = school().id
        val first = account("practice.a@test", "0779410002", Role.STUDENT, cohort, "Grade 6")
        val second = account("practice.b@test", "0779410003", Role.STUDENT, cohort, "Grade 6")
        val firstToken = token(first)
        val secondToken = token(second)

        val paper = generate(firstToken, """{"subject":"Mathematics"}""")
        check(paper.questionCount == 5)
        check(history(secondToken).isEmpty())
        check(cards(secondToken).none { it.id == paper.examId })
        mockMvc.perform(
            get("/exams/${paper.examId}/session").header("Authorization", auth(secondToken))
        ).andExpect(status().isNotFound)
        mockMvc.perform(
            get("/practice-papers/${paper.examId}/content").header("Authorization", auth(secondToken))
        ).andExpect(status().isNotFound)
        mockMvc.perform(
            post("/practice-papers/${paper.examId}/attempts").header("Authorization", auth(secondToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"score":1,"totalPoints":6,"percentage":17}""")
        ).andExpect(status().isNotFound)
    }

    @Test
    fun `attempts and best score are reported back on the paper`() {
        val admin = adminToken()
        seedReviewedPaper(admin)
        val learner = account("practice.scored@test", "0779410004", Role.STUDENT, school().id, "Grade 6")
        val learnerToken = token(learner)
        val paper = generate(learnerToken, """{"subject":"Mathematics","questionCount":2}""")

        // the public self-graded attempt route is what the history counts
        recordAttempt(learnerToken, paper.examId, score = 2, totalPoints = 4, percentage = 50)
        val first = history(learnerToken).single()
        check(first.attempts == 1)
        check(first.bestPercentage == 50)

        // a replayed attempt updates the same row rather than adding one
        recordAttempt(learnerToken, paper.examId, score = 4, totalPoints = 4, percentage = 100)
        val refreshed = history(learnerToken).single()
        check(refreshed.attempts == 1)
        check(refreshed.bestPercentage == 100)
    }

    @Test
    fun `a teacher sees a school learner's practice and a guardian their child's`() {
        val admin = adminToken()
        seedReviewedPaper(admin)
        val cohort = school().id
        val learner = account("practice.child@test", "0779410005", Role.STUDENT, cohort, "Grade 6")
        val teacher = account("practice.teacher@test", "0779410006", Role.TEACHER, cohort)
        val outsider = account("practice.outsider@test", "0779410007", Role.TEACHER, school().id)
        val guardian = account("practice.guardian@test", "0779410008", Role.PARENT, cohort)
        userRepository.save(learner.apply { parentUserId = guardian.id })
        val paper = generate(token(learner), """{"subject":"Mathematics"}""")

        val teacherRead = mockMvc.perform(
            get("/practice/student/${learner.id}").header("Authorization", auth(token(teacher)))
        ).andExpect(status().isOk).andReturn().response.contentAsString
        val seen: List<PracticePaperPayload> = objectMapper.readValue(
            teacherRead,
            objectMapper.typeFactory.constructCollectionType(List::class.java, PracticePaperPayload::class.java),
        )
        check(seen.map { it.examId } == listOf(paper.examId))

        // a teacher outside the school cannot tell the learner exists
        mockMvc.perform(
            get("/practice/student/${learner.id}").header("Authorization", auth(token(outsider)))
        ).andExpect(status().isNotFound)

        // the linked guardian can read the child's practice
        mockMvc.perform(
            get("/practice/child/${learner.id}").header("Authorization", auth(token(guardian)))
        ).andExpect(status().isOk).andReturn().response.contentAsString
            .let { check(it.contains(paper.examId)) }

        // an unlinked guardian cannot
        val stranger = account("practice.stranger@test", "0779410009", Role.PARENT, cohort)
        check(stranger.parentUserId == null)
        mockMvc.perform(
            get("/practice/child/${learner.id}").header("Authorization", auth(token(stranger)))
        ).andExpect(status().isNotFound)

        // and a learner cannot read someone else's practice as if they were staff
        mockMvc.perform(
            get("/practice/student/${learner.id}").header("Authorization", auth(token(learner)))
        ).andExpect(status().isForbidden)
    }

    @Test
    fun `refusals are explicit rather than fabricated`() {
        val admin = adminToken()
        seedReviewedPaper(admin, subject = "Mathematics")
        val learner = account("practice.refused@test", "0779410010", Role.STUDENT, school().id, "Grade 6")
        val learnerToken = token(learner)

        // nothing reviewed for this subject yet
        mockMvc.perform(
            post("/practice/generate").header("Authorization", auth(learnerToken))
                .contentType(MediaType.APPLICATION_JSON).content("""{"subject":"Astrophysics"}""")
        ).andExpect(status().isConflict)

        // missing subject and a nonsensical question count are rejected up front
        mockMvc.perform(
            post("/practice/generate").header("Authorization", auth(learnerToken))
                .contentType(MediaType.APPLICATION_JSON).content("""{"subject":"  "}""")
        ).andExpect(status().isBadRequest)
        mockMvc.perform(
            post("/practice/generate").header("Authorization", auth(learnerToken))
                .contentType(MediaType.APPLICATION_JSON).content("""{"subject":"Mathematics","questionCount":99}""")
        ).andExpect(status().isBadRequest)

        // a teacher account has no practice of its own to generate
        val staff = account("practice.staff@test", "0779410011", Role.TEACHER, school().id)
        mockMvc.perform(
            post("/practice/generate").header("Authorization", auth(token(staff)))
                .contentType(MediaType.APPLICATION_JSON).content("""{"subject":"Mathematics"}""")
        ).andExpect(status().isForbidden)

        // reads require a session
        mockMvc.perform(get("/practice/history")).andExpect(status().isUnauthorized)
        mockMvc.perform(get("/practice/student/${UUID.randomUUID()}")).andExpect(status().isUnauthorized)
    }

    @Test
    fun `a learner without a grade is told to fix the account`() {
        val admin = adminToken()
        seedReviewedPaper(admin)
        val learner = account("practice.nograde@test", "0779410012", Role.STUDENT, school().id)
        mockMvc.perform(
            post("/practice/generate").header("Authorization", auth(token(learner)))
                .contentType(MediaType.APPLICATION_JSON).content("""{"subject":"Mathematics"}""")
        ).andExpect(status().isBadRequest)
    }
}
