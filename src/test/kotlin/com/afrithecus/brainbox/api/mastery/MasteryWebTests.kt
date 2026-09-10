package com.afrithecus.brainbox.api.mastery

import com.afrithecus.brainbox.api.auth.web.AuthResponse
import com.afrithecus.brainbox.api.exams.entity.ExamEntity
import com.afrithecus.brainbox.api.exams.entity.ExamQuestionEntity
import com.afrithecus.brainbox.api.exams.model.ExamScope
import com.afrithecus.brainbox.api.exams.model.ExamStatus
import com.afrithecus.brainbox.api.exams.model.ExamType
import com.afrithecus.brainbox.api.exams.model.QuestionType
import com.afrithecus.brainbox.api.exams.repository.ExamQuestionRepository
import com.afrithecus.brainbox.api.exams.repository.ExamRepository
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import com.afrithecus.brainbox.api.mastery.web.MasteryOverviewPayload
import com.afrithecus.brainbox.api.mastery.web.MasteryUpdateRequest
import com.afrithecus.brainbox.api.mastery.web.SubjectMasterySummaryPayload
import com.afrithecus.brainbox.api.mastery.web.TopicMasteryPayload
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.util.UUID

/**
 * Topic mastery (doc 03 §6): cumulative scoring, subject resolution from exam
 * topics, bands, weak topics and self-only scoping.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class MasteryWebTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val userRepository: UserRepository,
    @Autowired private val examRepository: ExamRepository,
    @Autowired private val questionRepository: ExamQuestionRepository,
) {

    private fun auth(token: String) = "Bearer " + token

    private fun signup(phone: String): AuthResponse {
        val body = """{"name":"Mastery ${phone}","phoneNumber":"${phone}","password":"password123","role":"STUDENT"}"""
        val response = mockMvc.perform(
            post("/auth/signup").header("X-Device-Id", "dev")
                .contentType(MediaType.APPLICATION_JSON).content(body)
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return objectMapper.readValue(response, AuthResponse::class.java)
    }

    private fun seedTopic(topic: String, subject: String, createdBy: UUID) {
        val exam = examRepository.save(ExamEntity().apply {
            title = "Mastery " + subject
            this.subject = subject
            examType = ExamType.DIGITAL
            scope = ExamScope.GLOBAL
            durationMinutes = 30
            questionCount = 1
            status = ExamStatus.PUBLISHED
            this.createdBy = createdBy
        })
        questionRepository.save(ExamQuestionEntity().apply {
            examId = exam.id
            text = topic + " question"
            qType = QuestionType.MCQ
            this.topic = topic
            points = 1
            orderIndex = 0
        })
    }

    private fun update(student: AuthResponse, topicId: String, correct: Int, total: Int, time: Int = 300): TopicMasteryPayload {
        val body = objectMapper.writeValueAsString(MasteryUpdateRequest(topicId, correct, total, time))
        val response = mockMvc.perform(
            post("/mastery/user/${student.user.id}/update").header("Authorization", auth(student.sessionToken!!))
                .contentType(MediaType.APPLICATION_JSON).content(body)
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return objectMapper.readValue(response, TopicMasteryPayload::class.java)
    }

    @Test
    fun `mastery accumulates and resolves the subject from exam topics`() {
        val student = signup("0778600001")
        seedTopic("Algebra", "Mathematics", UUID.fromString(student.user.id))
        val first = update(student, "Algebra", 8, 10)
        check(first.topicName == "Algebra")
        check(first.subject == "Mathematics")
        check(first.score == 80f)
        check(first.level == "ADVANCED")
        check(first.attemptsCount == 1)
        check(first.averageTimePerQuestion == 30f)
        check(first.trend == 80f)

        val second = update(student, "Algebra", 5, 10)
        check(second.score == 65f)
        check(second.level == "PROFICIENT")
        check(second.attemptsCount == 2)
        check(second.questionsAttempted == 20)
        check(second.correctAnswers == 13)
        check(second.trend == -15f)

        val overviewBody = mockMvc.perform(
            get("/mastery/user/${student.user.id}").header("Authorization", auth(student.sessionToken!!))
        ).andExpect(status().isOk).andReturn().response.contentAsString
        val overview = objectMapper.readValue(overviewBody, MasteryOverviewPayload::class.java)
        check(overview.totalTopics == 1)
        check(overview.proficientCount == 1)
        check(overview.overallMastery == 65f)
        check(overview.subjectsSummary.size == 1)
        check(overview.subjectsSummary.single().subject == "Mathematics")
        check(overview.subjectsSummary.single().overallScore == 65f)
        check(overview.recentImprovements.isEmpty())

        val subjectBody = mockMvc.perform(
            get("/mastery/user/${student.user.id}/subject/Mathematics").header("Authorization", auth(student.sessionToken!!))
        ).andExpect(status().isOk).andReturn().response.contentAsString
        val subject = objectMapper.readValue(subjectBody, SubjectMasterySummaryPayload::class.java)
        check(subject.totalTopics == 1)
        check(subject.weakestTopic?.topicName == "Algebra")
        check(subject.recommendedFocus == "Algebra")
    }

    @Test
    fun `weak topics surface and validation is enforced`() {
        val student = signup("0778600002")
        seedTopic("Geometry", "Mathematics", UUID.fromString(student.user.id))
        update(student, "Geometry", 2, 10)
        val weakBody = mockMvc.perform(
            get("/mastery/user/${student.user.id}/weak-topics").header("Authorization", auth(student.sessionToken!!))
        ).andExpect(status().isOk).andReturn().response.contentAsString
        val weak = objectMapper.readValue(weakBody, Array<TopicMasteryPayload>::class.java)
        check(weak.size == 1)
        check(weak.single().level == "NOVICE")

        // unknown topic still records, under the General subject
        val general = update(student, "Unmapped Topic", 5, 10)
        check(general.subject == "General")

        mockMvc.perform(
            post("/mastery/user/${student.user.id}/update").header("Authorization", auth(student.sessionToken!!))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(MasteryUpdateRequest("Geometry", 11, 10, 10)))
        ).andExpect(status().isBadRequest)
        mockMvc.perform(
            post("/mastery/user/${student.user.id}/update").header("Authorization", auth(student.sessionToken!!))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(MasteryUpdateRequest("Geometry", 1, 0, 10)))
        ).andExpect(status().isBadRequest)
    }

    @Test
    fun `mastery is self only`() {
        val a = signup("0778600003")
        val b = signup("0778600004")
        mockMvc.perform(get("/mastery/user/${b.user.id}").header("Authorization", auth(a.sessionToken!!)))
            .andExpect(status().isForbidden)
        mockMvc.perform(
            post("/mastery/user/${b.user.id}/update").header("Authorization", auth(a.sessionToken!!))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(MasteryUpdateRequest("Geometry", 1, 2, 10)))
        ).andExpect(status().isForbidden)
    }
}
