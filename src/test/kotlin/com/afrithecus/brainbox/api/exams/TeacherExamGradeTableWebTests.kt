package com.afrithecus.brainbox.api.exams

import com.afrithecus.brainbox.api.auth.web.AuthResponse
import com.afrithecus.brainbox.api.exams.entity.ExamEntity
import com.afrithecus.brainbox.api.exams.entity.ExamSubmissionEntity
import com.afrithecus.brainbox.api.exams.repository.ExamRepository
import com.afrithecus.brainbox.api.exams.repository.ExamSubmissionRepository
import com.afrithecus.brainbox.api.exams.web.ExamGradeAnalysisPayload
import com.afrithecus.brainbox.api.exams.web.ExamGradeRowPayload
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.model.Role
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

/**
 * Adaptive exam grade table + distribution (`GET /teacher/exams/{examId}/grade-table`,
 * `/grade-analysis`) — the names the web's exam-analytics screen reads but the API only
 * exposed the raw `/analysis` report for.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class TeacherExamGradeTableWebTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val userRepository: UserRepository,
    @Autowired private val examRepository: ExamRepository,
    @Autowired private val submissionRepository: ExamSubmissionRepository,
    @Autowired private val passwordEncoder: PasswordEncoder,
) {

    private fun user(name: String, phone: String): UserEntity = userRepository.save(UserEntity().apply {
        this.name = name
        phoneNumber = phone
        email = phone + "@examgrades.test"
        passwordHash = passwordEncoder.encode("password123") ?: error("encode")
        role = Role.TEACHER
        isActive = true
        isVerified = true
    })

    private fun token(entity: UserEntity): String {
        val body = mockMvc.perform(
            post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"identifier\":\"" + entity.phoneNumber + "\",\"password\":\"password123\"}")
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return objectMapper.readValue(body, AuthResponse::class.java).sessionToken!!
    }

    @Test
    fun `grade table and distribution are derived from the analysis report`() {
        val teacher = user("Exam Author", "0799900001")
        val other = user("Other Teacher", "0799900002")
        val exam = examRepository.save(ExamEntity().apply {
            clientId = "exam_grades_1"
            title = "Algebra Check"
            subject = "Mathematics"
            examType = com.afrithecus.brainbox.api.exams.model.ExamType.DIGITAL
            scope = com.afrithecus.brainbox.api.exams.model.ExamScope.GLOBAL
            createdBy = teacher.id
            totalPoints = 100
            questionCount = 5
        })
        // 90 / 72 / 55 / 30 percent: one learner per EE, ME, AE and BE band.
        listOf(90, 72, 55, 30).forEachIndexed { index, percentage ->
            submissionRepository.save(ExamSubmissionEntity().apply {
                examId = exam.id
                userId = userRepository.save(UserEntity().apply {
                    name = "Learner " + (index + 1)
                    phoneNumber = "07999100" + index
                    email = phoneNumber + "@learner.test"
                    passwordHash = passwordEncoder.encode("1234") ?: error("encode")
                    role = Role.STUDENT
                    isActive = true
                    isVerified = true
                    credentialKind = "PIN"
                }).id
                score = percentage
                totalPoints = 100
                this.percentage = percentage
                correctCount = percentage / 20
                questionCount = 5
            })
        }
        val t = token(teacher)

        val tableBody = mockMvc.perform(
            get("/teacher/exams/" + exam.id + "/grade-table").header("Authorization", "Bearer " + t)
        ).andExpect(status().isOk).andReturn().response.contentAsString
        val rows = objectMapper.readValue(tableBody, Array<ExamGradeRowPayload>::class.java).toList()
        check(rows.size == 4) { "rows=" + rows.size }
        check(rows.map { it.percentage } == listOf(90.0, 72.0, 55.0, 30.0)) { "order=" + rows.map { it.percentage } }
        check(rows.map { it.rank } == listOf(1, 2, 3, 4))
        check(rows.map { it.gradeBand } == listOf("EE", "ME", "AE", "DE")) { "bands=" + rows.map { it.gradeBand } }
        check(rows.all { it.totalScore == it.score && it.maxScore == 100 })

        val analysisBody = mockMvc.perform(
            get("/teacher/exams/" + exam.id + "/grade-analysis").header("Authorization", "Bearer " + t)
        ).andExpect(status().isOk).andReturn().response.contentAsString
        val analysis = objectMapper.readValue(analysisBody, ExamGradeAnalysisPayload::class.java)
        check(analysis.examTitle == "Algebra Check")
        check(analysis.examId == "exam_grades_1")
        check(analysis.totalStudents == 4)
        check(analysis.averageScore == 61.75) { "average=" + analysis.averageScore }
        val counts = analysis.gradeAnalysis.associate { it.grade to it.count }
        check(counts == mapOf("EE" to 1, "ME" to 1, "AE" to 1, "BE" to 0, "DE" to 1, "E" to 0)) { "counts=" + counts }
        check(analysis.gradeAnalysis.sumOf { it.percentage } == 100.0)

        // Another teacher must not read this exam's analysis (403 for a foreign exam, never
        // a foreign 200 — `TeacherExamService.ownExam` treats another author's exam as forbidden).
        mockMvc.perform(
            get("/teacher/exams/" + exam.id + "/grade-table").header("Authorization", "Bearer " + token(other))
        ).andExpect(status().isForbidden)
    }
}
