package com.afrithecus.brainbox.api.analytics

import com.afrithecus.brainbox.api.analytics.web.ClassAnalyticsPayload
import com.afrithecus.brainbox.api.analytics.web.StudentPerformancePayload
import com.afrithecus.brainbox.api.analytics.web.SubjectDetailPayload
import com.afrithecus.brainbox.api.auth.web.AuthResponse
import com.afrithecus.brainbox.api.classes.entity.ClassMembershipEntity
import com.afrithecus.brainbox.api.classes.entity.TeacherClassEntity
import com.afrithecus.brainbox.api.classes.repository.ClassMembershipRepository
import com.afrithecus.brainbox.api.classes.repository.TeacherClassRepository
import com.afrithecus.brainbox.api.exams.entity.ExamEntity
import com.afrithecus.brainbox.api.exams.entity.ExamQuestionEntity
import com.afrithecus.brainbox.api.exams.entity.ExamSubmissionEntity
import com.afrithecus.brainbox.api.exams.model.ExamScope
import com.afrithecus.brainbox.api.exams.model.ExamStatus
import com.afrithecus.brainbox.api.exams.model.ExamType
import com.afrithecus.brainbox.api.exams.model.QuestionType
import com.afrithecus.brainbox.api.exams.repository.ExamQuestionRepository
import com.afrithecus.brainbox.api.exams.repository.ExamRepository
import com.afrithecus.brainbox.api.exams.repository.ExamSubmissionRepository
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
import java.time.Instant
import java.util.UUID

/**
 * Server-computed exam analytics (doc 02 §8): per-student aggregates, subject
 * topic breakdown, class distribution and relationship-based authorization.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AnalyticsWebTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val userRepository: UserRepository,
    @Autowired private val schoolRepository: SchoolRepository,
    @Autowired private val classRepository: TeacherClassRepository,
    @Autowired private val membershipRepository: ClassMembershipRepository,
    @Autowired private val examRepository: ExamRepository,
    @Autowired private val questionRepository: ExamQuestionRepository,
    @Autowired private val submissionRepository: ExamSubmissionRepository,
    @Autowired private val passwordEncoder: PasswordEncoder,
) {

    private fun auth(token: String) = "Bearer " + token

    private fun signup(phone: String, role: String = "STUDENT"): AuthResponse {
        val body = """{"name":"Analytics ${phone}","phoneNumber":"${phone}","password":"password123","role":"${role}"}"""
        val response = mockMvc.perform(
            post("/auth/signup").header("X-Device-Id", "dev")
                .contentType(MediaType.APPLICATION_JSON).content(body)
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return objectMapper.readValue(response, AuthResponse::class.java)
    }

    private fun newUser(phone: String, email: String, role: Role, schoolId: UUID?): UserEntity {
        val user = UserEntity().apply {
            this.phoneNumber = phone
            this.email = email
            passwordHash = passwordEncoder.encode("password123") ?: error("encode")
            name = "Analytics " + phone
            this.role = role
            this.schoolId = schoolId
            isActive = true
            isVerified = true
        }
        return userRepository.save(user)
    }

    private fun login(email: String): String {
        val body = mockMvc.perform(
            post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("""{"identifier":"${email}","password":"password123"}""")
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return objectMapper.readValue(body, AuthResponse::class.java).sessionToken!!
    }

    private class Fixture(
        val school: SchoolEntity,
        val teacher: UserEntity,
        val teacherToken: String,
        val student: UserEntity,
        val studentToken: String,
        val peer: UserEntity,
        val clazz: TeacherClassEntity,
        val exam: ExamEntity,
    )

    private fun fixture(): Fixture {
        val school = schoolRepository.save(SchoolEntity().apply { name = "Analytics High" })
        val teacher = newUser("0778200100", "analytics.teacher@test", Role.TEACHER, school.id)
        val teacherToken = login("analytics.teacher@test")
        val studentResp = signup("0778200101")
        val student = userRepository.findById(UUID.fromString(studentResp.user.id)).orElseThrow().apply {
            schoolId = school.id
            gradeLevel = "Form 3"
        }
        userRepository.save(student)
        val peer = newUser("0778200102", "analytics.peer@test", Role.STUDENT, school.id)
        val clazz = classRepository.save(TeacherClassEntity().apply {
            teacherUserId = teacher.id
            this.schoolId = school.id
            name = "Form 3 East"
            gradeLevel = "Form 3"
            subject = "Mathematics"
        })
        membershipRepository.save(ClassMembershipEntity().apply {
            classId = clazz.id
            studentId = student.id
        })
        val exam = examRepository.save(ExamEntity().apply {
            title = "Math Midterm"
            subject = "Mathematics"
            examType = ExamType.DIGITAL
            scope = ExamScope.GLOBAL
            durationMinutes = 60
            questionCount = 2
            status = ExamStatus.PUBLISHED
            difficulty = 2
            createdBy = teacher.id
        })
        val q1 = questionRepository.save(ExamQuestionEntity().apply {
            examId = exam.id
            text = "Solve for x"
            qType = QuestionType.MCQ
            points = 2
            topic = "Algebra"
            difficulty = 2
            orderIndex = 0
        })
        val q2 = questionRepository.save(ExamQuestionEntity().apply {
            examId = exam.id
            text = "Angle sum"
            qType = QuestionType.MCQ
            points = 2
            topic = "Geometry"
            difficulty = 5
            orderIndex = 1
        })
        // student: 3/4 (Algebra right, Geometry wrong); peer: 1/4
        submissionRepository.save(submission(exam.id, student.id, 3, 75, q1.id.toString(), q2.id.toString(), Instant.now().minusSeconds(3600)))
        submissionRepository.save(submission(exam.id, peer.id, 1, 25, q1.id.toString(), q2.id.toString(), Instant.now().minusSeconds(7200)))
        return Fixture(school, teacher, teacherToken, student, studentResp.sessionToken!!, peer, clazz, exam)
    }

    private fun submission(examId: UUID, userId: UUID, score: Int, percentage: Int, rightId: String, wrongId: String, at: Instant): ExamSubmissionEntity =
        ExamSubmissionEntity().apply {
            this.examId = examId
            this.userId = userId
            this.score = score
            totalPoints = 4
            this.percentage = percentage
            grade = "C"
            correctCount = if (percentage >= 50) 1 else 0
            questionCount = 2
            timeTakenSeconds = 120
            submittedAt = at
            questionResults = objectMapper.writeValueAsString(
                listOf(
                    mapOf("questionId" to rightId, "isCorrect" to (percentage >= 50), "pointsEarned" to (if (percentage >= 50) 2 else 0)),
                    mapOf("questionId" to wrongId, "isCorrect" to false, "pointsEarned" to 0),
                )
            )
        }

    @Test
    fun `student performance is server computed with percentile and topic breakdown`() {
        val f = fixture()
        val body = mockMvc.perform(
            get("/analytics/student/${f.student.id}").header("Authorization", auth(f.studentToken))
        ).andExpect(status().isOk).andReturn().response.contentAsString
        val performance = objectMapper.readValue(body, StudentPerformancePayload::class.java)
        check(performance.student.id == f.student.id.toString())
        check(performance.gradeLevel == "Form 3")
        check(performance.overallPercentage == 75.0)
        check(performance.overallGrade == "ME")
        check(performance.subjects.size == 1)
        check(performance.subjects.single().subject == "Mathematics")
        check(performance.subjects.single().grade == "ME")
        check(performance.strengths.contains("Mathematics"))
        check(performance.examHistory.size == 1)
        check(performance.examHistory.single().percentile == 100)
        check(performance.examHistory.single().markingType == "AUTOMATIC")
        check(performance.examHistory.single().gradingDetails.size == 2)
        check(performance.examHistory.single().weakAreas.any { it.cbcStrand == "Geometry" })
        check(performance.recommendedActions.isNotEmpty())
    }

    @Test
    fun `subject detail aggregates topic mastery`() {
        val f = fixture()
        val body = mockMvc.perform(
            get("/analytics/student/${f.student.id}/subject/Mathematics").header("Authorization", auth(f.studentToken))
        ).andExpect(status().isOk).andReturn().response.contentAsString
        val detail = objectMapper.readValue(body, SubjectDetailPayload::class.java)
        check(detail.overallScore == 75.0)
        check(detail.overallGrade == "ME")
        val algebra = detail.topicBreakdown.first { it.topic == "Algebra" }
        val geometry = detail.topicBreakdown.first { it.topic == "Geometry" }
        check(algebra.score == 100.0)
        check(algebra.masteryLevel == "EE")
        check(geometry.score == 0.0)
        check(geometry.masteryLevel == "BE")
        check(detail.recommendedActions.any { it.contains("Geometry") })
    }

    @Test
    fun `class analytics are teacher scoped with distribution and rankings`() {
        val f = fixture()
        val body = mockMvc.perform(
            get("/analytics/class/${f.clazz.id}").header("Authorization", auth(f.teacherToken))
        ).andExpect(status().isOk).andReturn().response.contentAsString
        val analytics = objectMapper.readValue(body, ClassAnalyticsPayload::class.java)
        check(analytics.className == "Form 3 East")
        check(analytics.totalStudents == 1)
        check(analytics.classAverage == 75.0)
        check(analytics.gradeDistribution["ME"] == 1)
        check(analytics.students.single().rank == 1)
        check(analytics.subjectPerformance.single().classMean == 75.0)
        check(analytics.subjectPerformance.single().passRate == 100.0)
        check(analytics.examHistory.isNotEmpty())

        // a teacher who does not own the class is forbidden
        val outsider = newUser("0778200103", "analytics.outsider@test", Role.TEACHER, f.school.id)
        val outsiderToken = login("analytics.outsider@test")
        mockMvc.perform(get("/analytics/class/${f.clazz.id}").header("Authorization", auth(outsiderToken)))
            .andExpect(status().isForbidden)
        // students never see class analytics
        mockMvc.perform(get("/analytics/class/${f.clazz.id}").header("Authorization", auth(f.studentToken)))
            .andExpect(status().isForbidden)
    }

    @Test
    fun `analytics reads are relationship scoped`() {
        val f = fixture()
        // another student cannot read the first student's analytics
        val stranger = signup("0778200104")
        mockMvc.perform(get("/analytics/student/${f.student.id}").header("Authorization", auth(stranger.sessionToken!!)))
            .andExpect(status().isForbidden)
        // the owning teacher can
        mockMvc.perform(get("/analytics/student/${f.student.id}").header("Authorization", auth(f.teacherToken)))
            .andExpect(status().isOk)
        // a linked parent can
        val parent = newUser("0778200105", "analytics.parent@test", Role.PARENT, f.school.id)
        val parentToken = login("analytics.parent@test")
        f.student.parentUserId = parent.id
        userRepository.save(f.student)
        mockMvc.perform(get("/analytics/student/${f.student.id}").header("Authorization", auth(parentToken)))
            .andExpect(status().isOk)
        // unknown identifiers are rejected
        mockMvc.perform(get("/analytics/student/${UUID.randomUUID()}").header("Authorization", auth(f.teacherToken)))
            .andExpect(status().isNotFound)
        mockMvc.perform(get("/analytics/student/not-a-uuid").header("Authorization", auth(f.teacherToken)))
            .andExpect(status().isBadRequest)
    }
}
