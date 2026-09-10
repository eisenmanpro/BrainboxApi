package com.afrithecus.brainbox.api.career

import com.afrithecus.brainbox.api.auth.web.AuthResponse
import com.afrithecus.brainbox.api.career.web.CareerGoalPayload
import com.afrithecus.brainbox.api.career.web.CareerPathPayload
import com.afrithecus.brainbox.api.career.web.CareerRecommendationPayload
import com.afrithecus.brainbox.api.career.web.ElectiveSubjectPayload
import com.afrithecus.brainbox.api.career.web.MatchingSchoolPayload
import com.afrithecus.brainbox.api.career.web.MentorRequestResultPayload
import com.afrithecus.brainbox.api.career.web.SubjectSaveResultPayload
import com.afrithecus.brainbox.api.exams.entity.ExamEntity
import com.afrithecus.brainbox.api.exams.entity.ExamSubmissionEntity
import com.afrithecus.brainbox.api.exams.model.ExamScope
import com.afrithecus.brainbox.api.exams.model.ExamStatus
import com.afrithecus.brainbox.api.exams.model.ExamType
import com.afrithecus.brainbox.api.exams.repository.ExamRepository
import com.afrithecus.brainbox.api.exams.repository.ExamSubmissionRepository
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.util.UUID

/**
 * Career guidance, goals, electives and school matching (doc 06 §1/§4). Verifies
 * server-derived recommendations from real performance, self-only scoping and the
 * reference catalogs.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class CareerWebTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val userRepository: UserRepository,
    @Autowired private val examRepository: ExamRepository,
    @Autowired private val submissionRepository: ExamSubmissionRepository,
) {

    private fun auth(token: String) = "Bearer " + token

    private fun signup(phone: String): AuthResponse {
        val body = """{"name":"Career ${phone}","phoneNumber":"${phone}","password":"password123","role":"STUDENT"}"""
        val response = mockMvc.perform(
            post("/auth/signup").header("X-Device-Id", "dev")
                .contentType(MediaType.APPLICATION_JSON).content(body)
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return objectMapper.readValue(response, AuthResponse::class.java)
    }

    /** A student in Junior School with one graded Mathematics exam at 88%. */
    private fun juniorStudent(phone: String): Pair<AuthResponse, UUID> {
        val auth = signup(phone)
        val student = userRepository.findById(UUID.fromString(auth.user.id)).orElseThrow().apply {
            gradeLevel = "Grade 8"
        }
        userRepository.save(student)
        val exam = examRepository.save(ExamEntity().apply {
            title = "Careers Maths"
            subject = "Mathematics"
            examType = ExamType.DIGITAL
            scope = ExamScope.GLOBAL
            durationMinutes = 30
            questionCount = 1
            status = ExamStatus.PUBLISHED
            createdBy = student.id
        })
        submissionRepository.save(ExamSubmissionEntity().apply {
            examId = exam.id
            userId = student.id
            score = 88
            totalPoints = 100
            percentage = 88
            grade = "A"
            correctCount = 1
            questionCount = 1
            submittedAt = Instant.now()
            questionResults = "[]"
        })
        return auth to student.id
    }

    @Test
    fun `recommendations derive from performance and curriculum`() {
        val (student, id) = juniorStudent("0778400001")
        val body = mockMvc.perform(
            get("/career/recommendations/${id}?gradeBand=JUNIOR_SCHOOL").header("Authorization", auth(student.sessionToken!!))
        ).andExpect(status().isOk).andReturn().response.contentAsString
        val rec = objectMapper.readValue(body, CareerRecommendationPayload::class.java)
        check(rec.careerGoal == "GENERAL")
        check(rec.gradeBand == "JUNIOR_SCHOOL")
        check(rec.learningPath.isNotEmpty())
        check(rec.skillGaps.isNotEmpty())
        check(rec.mentorMatches.isNotEmpty())
        check(rec.scholarships.isNotEmpty())
        check(rec.orientationAnalysis != null)
        check(rec.orientationAnalysis!!.pillars.size == 4)
        check(rec.salaryInsights.isNotEmpty())
        check(rec.discoverySignals.isNotEmpty())
        check(rec.growthPlan.isNotEmpty())
        check(rec.achievements.isNotEmpty())
        check(rec.interviewFocus.isNotBlank())
    }

    @Test
    fun `setting a goal personalises the plan with real scores`() {
        val (student, id) = juniorStudent("0778400002")
        val body = mockMvc.perform(
            post("/career/set-goal?userId=${id}&goal=DOCTOR&gradeBand=JUNIOR_SCHOOL")
                .header("Authorization", auth(student.sessionToken!!))
        ).andExpect(status().isOk).andReturn().response.contentAsString
        val rec = objectMapper.readValue(body, CareerRecommendationPayload::class.java)
        check(rec.careerGoal == "DOCTOR")
        val maths = rec.skillGaps.first { it.skill == "Mathematics" }
        check(maths.userScore == 88)
        check(rec.achievements.any { it.title == "Goal Set" && it.unlocked })

        // goal persists across reads
        val again = mockMvc.perform(
            get("/career/recommendations/${id}").header("Authorization", auth(student.sessionToken!!))
        ).andExpect(status().isOk).andReturn().response.contentAsString
        check(objectMapper.readValue(again, CareerRecommendationPayload::class.java).careerGoal == "DOCTOR")
    }

    @Test
    fun `mentor requests are idempotent and reflected in matches`() {
        val (student, id) = juniorStudent("0778400003")
        val body = mockMvc.perform(
            get("/career/recommendations/${id}").header("Authorization", auth(student.sessionToken!!))
        ).andExpect(status().isOk).andReturn().response.contentAsString
        val mentorId = objectMapper.readValue(body, CareerRecommendationPayload::class.java).mentorMatches.first().id

        val first = mockMvc.perform(
            post("/career/mentor/request/${mentorId}?userId=${id}").header("Authorization", auth(student.sessionToken!!))
        ).andExpect(status().isOk).andReturn().response.contentAsString
        check(objectMapper.readValue(first, MentorRequestResultPayload::class.java).status == "success")
        mockMvc.perform(
            post("/career/mentor/request/${mentorId}?userId=${id}").header("Authorization", auth(student.sessionToken!!))
        ).andExpect(status().isOk)

        val after = mockMvc.perform(
            get("/career/recommendations/${id}").header("Authorization", auth(student.sessionToken!!))
        ).andExpect(status().isOk).andReturn().response.contentAsString
        val matches = objectMapper.readValue(after, CareerRecommendationPayload::class.java).mentorMatches
        check(matches.first { it.id == mentorId }.isRequested)
    }

    @Test
    fun `elective subjects list, save and validate`() {
        val (student, id) = juniorStudent("0778400004")
        val body = mockMvc.perform(
            get("/career/subjects?userId=${id}&gradeBand=JUNIOR_SCHOOL").header("Authorization", auth(student.sessionToken!!))
        ).andExpect(status().isOk).andReturn().response.contentAsString
        val subjects = objectMapper.readValue(body, Array<ElectiveSubjectPayload>::class.java)
        check(subjects.isNotEmpty())
        check(subjects.all { it.name.isNotBlank() })

        val chosen = objectMapper.writeValueAsString(subjects.take(2).map { it.id })
        val saved = mockMvc.perform(
            post("/career/subjects/save?userId=${id}").header("Authorization", auth(student.sessionToken!!))
                .contentType(MediaType.APPLICATION_JSON).content(chosen)
        ).andExpect(status().isOk).andReturn().response.contentAsString
        check(objectMapper.readValue(saved, SubjectSaveResultPayload::class.java).savedCount == 2)

        mockMvc.perform(
            post("/career/subjects/save?userId=${id}").header("Authorization", auth(student.sessionToken!!))
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(listOf(UUID.randomUUID().toString())))
        ).andExpect(status().isBadRequest)
    }

    @Test
    fun `school matching is ranked and filterable`() {
        val (student, id) = juniorStudent("0778400005")
        val body = mockMvc.perform(
            get("/career/schools/matching?userId=${id}").header("Authorization", auth(student.sessionToken!!))
        ).andExpect(status().isOk).andReturn().response.contentAsString
        val schools = objectMapper.readValue(body, Array<MatchingSchoolPayload>::class.java)
        check(schools.size >= 3)
        check(schools.first().matchScore >= schools.last().matchScore)

        val filtered = mockMvc.perform(
            get("/career/schools/matching?userId=${id}&type=Triple").header("Authorization", auth(student.sessionToken!!))
        ).andExpect(status().isOk).andReturn().response.contentAsString
        check(objectMapper.readValue(filtered, Array<MatchingSchoolPayload>::class.java).all { it.type == "Triple" })

        val search = mockMvc.perform(
            get("/career/schools/search?query=Nairobi").header("Authorization", auth(student.sessionToken!!))
        ).andExpect(status().isOk).andReturn().response.contentAsString
        val found = objectMapper.readValue(search, Array<MatchingSchoolPayload>::class.java)
        check(found.isNotEmpty())
        val detail = mockMvc.perform(
            get("/career/schools/${found.first().id}").header("Authorization", auth(student.sessionToken!!))
        ).andExpect(status().isOk).andReturn().response.contentAsString
        check(objectMapper.readValue(detail, MatchingSchoolPayload::class.java).name == found.first().name)
    }

    @Test
    fun `goal plans support crud and career path exposes steps`() {
        val (student, id) = juniorStudent("0778400006")
        val createBody = """{"userId":"${id}","careerId":"LAWYER","milestones":["Join debate club","Read a law book"]}"""
        val created = mockMvc.perform(
            post("/career/goals").header("Authorization", auth(student.sessionToken!!))
                .contentType(MediaType.APPLICATION_JSON).content(createBody)
        ).andExpect(status().isOk).andReturn().response.contentAsString
        val goal = objectMapper.readValue(created, CareerGoalPayload::class.java)
        check(goal.careerId == "LAWYER")
        check(goal.milestones.size == 2)

        val list = mockMvc.perform(
            get("/career/goals/${id}").header("Authorization", auth(student.sessionToken!!))
        ).andExpect(status().isOk).andReturn().response.contentAsString
        check(objectMapper.readValue(list, Array<CareerGoalPayload>::class.java).any { it.id == goal.id })

        val updated = mockMvc.perform(
            put("/career/goals/${goal.id}").header("Authorization", auth(student.sessionToken!!))
                .contentType(MediaType.APPLICATION_JSON).content("""{"status":"COMPLETED"}""")
        ).andExpect(status().isOk).andReturn().response.contentAsString
        check(objectMapper.readValue(updated, CareerGoalPayload::class.java).status == "COMPLETED")

        mockMvc.perform(
            delete("/career/goals/${goal.id}").header("Authorization", auth(student.sessionToken!!))
        ).andExpect(status().isNoContent)

        val path = mockMvc.perform(
            get("/career/path/DOCTOR").header("Authorization", auth(student.sessionToken!!))
        ).andExpect(status().isOk).andReturn().response.contentAsString
        val careerPath = objectMapper.readValue(path, CareerPathPayload::class.java)
        check(careerPath.steps.size == 3)
    }

    @Test
    fun `career profile is self only`() {
        val (a, idA) = juniorStudent("0778400007")
        val (b, idB) = juniorStudent("0778400008")
        mockMvc.perform(get("/career/recommendations/${idB}").header("Authorization", auth(a.sessionToken!!)))
            .andExpect(status().isForbidden)
        mockMvc.perform(post("/career/set-goal?userId=${idB}&goal=DOCTOR").header("Authorization", auth(a.sessionToken!!)))
            .andExpect(status().isForbidden)
        mockMvc.perform(get("/career/goals/${idB}").header("Authorization", auth(a.sessionToken!!)))
            .andExpect(status().isForbidden)
        mockMvc.perform(post("/career/set-goal?userId=${idA}&goal=NOPE").header("Authorization", auth(a.sessionToken!!)))
            .andExpect(status().isBadRequest)
    }
}
