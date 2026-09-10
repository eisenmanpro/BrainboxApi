package com.afrithecus.brainbox.api.dashboard

import com.afrithecus.brainbox.api.auth.web.AuthResponse
import com.afrithecus.brainbox.api.classes.entity.ClassMembershipEntity
import com.afrithecus.brainbox.api.classes.entity.TeacherClassEntity
import com.afrithecus.brainbox.api.classes.repository.ClassMembershipRepository
import com.afrithecus.brainbox.api.classes.repository.TeacherClassRepository
import com.afrithecus.brainbox.api.contests.entity.ContestEntity
import com.afrithecus.brainbox.api.contests.model.ContestLifecycle
import com.afrithecus.brainbox.api.contests.repository.ContestRepository
import com.afrithecus.brainbox.api.dashboard.web.AssignmentPayload
import com.afrithecus.brainbox.api.dashboard.web.ContestPayload
import com.afrithecus.brainbox.api.dashboard.web.DashboardInsightsPayload
import com.afrithecus.brainbox.api.dashboard.web.QuickActionPayload
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
import com.afrithecus.brainbox.api.homework.entity.HomeworkEntity
import com.afrithecus.brainbox.api.homework.model.HomeworkScope
import com.afrithecus.brainbox.api.homework.model.SubmissionType
import com.afrithecus.brainbox.api.homework.repository.HomeworkRepository
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
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Student dashboard (BACKEND_BLUEPRINT §2): urgent assignments, contest cards,
 * quick actions and the server-computed insight panel.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class DashboardWebTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val userRepository: UserRepository,
    @Autowired private val schoolRepository: SchoolRepository,
    @Autowired private val classRepository: TeacherClassRepository,
    @Autowired private val membershipRepository: ClassMembershipRepository,
    @Autowired private val homeworkRepository: HomeworkRepository,
    @Autowired private val examRepository: ExamRepository,
    @Autowired private val questionRepository: ExamQuestionRepository,
    @Autowired private val submissionRepository: ExamSubmissionRepository,
    @Autowired private val contestRepository: ContestRepository,
    @Autowired private val passwordEncoder: PasswordEncoder,
) {

    private fun auth(token: String) = "Bearer " + token

    private fun newUser(phone: String, email: String, role: Role, schoolId: UUID?): UserEntity =
        userRepository.save(UserEntity().apply {
            this.phoneNumber = phone
            this.email = email
            passwordHash = passwordEncoder.encode("password123") ?: error("encode")
            name = "Dashboard " + phone
            this.role = role
            this.schoolId = schoolId
            isActive = true
            isVerified = true
        })

    private fun signup(phone: String): AuthResponse {
        val body = """{"name":"Dash ${phone}","phoneNumber":"${phone}","password":"password123","role":"STUDENT"}"""
        val response = mockMvc.perform(
            post("/auth/signup").header("X-Device-Id", "dev")
                .contentType(MediaType.APPLICATION_JSON).content(body)
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return objectMapper.readValue(response, AuthResponse::class.java)
    }

    private class Fixture(
        val student: UserEntity,
        val studentToken: String,
        val clazz: TeacherClassEntity,
    )

    private fun fixture(): Fixture {
        val school = schoolRepository.save(SchoolEntity().apply { name = "Dashboard High" })
        val teacher = newUser("0778300100", "dashboard.teacher@test", Role.TEACHER, school.id)
        val studentResp = signup("0778300101")
        val student = userRepository.findById(UUID.fromString(studentResp.user.id)).orElseThrow().apply {
            schoolId = school.id
            gradeLevel = "Form 3"
        }
        userRepository.save(student)
        val clazz = classRepository.save(TeacherClassEntity().apply {
            teacherUserId = teacher.id
            this.schoolId = school.id
            name = "Form 3 West"
            gradeLevel = "Form 3"
            subject = "Mathematics"
        })
        membershipRepository.save(ClassMembershipEntity().apply {
            classId = clazz.id
            studentId = student.id
        })
        val now = Instant.now()
        homeworkRepository.save(HomeworkEntity().apply {
            id = "hw_dash_1"
            classId = clazz.id
            teacherId = teacher.id
            teacherName = "Mr. Kiplagat"
            this.schoolId = school.id
            title = "Quadratic worksheet"
            description = "Complete all questions"
            subject = "Mathematics"
            gradeLevel = 8
            dueDate = now.plus(1, ChronoUnit.DAYS)
            submissionType = SubmissionType.FREE_TEXT
            scope = HomeworkScope.SCHOOL_GRADE_CLASS
            isActive = true
            isDraft = false
            createdAt = now
            updatedAt = now
        })
        // one graded exam so insights, streaks and peer comparison have data
        val peer = newUser("0778300102", "dashboard.peer@test", Role.STUDENT, school.id)
        val exam = examRepository.save(ExamEntity().apply {
            title = "Dashboard Maths"
            subject = "Mathematics"
            examType = ExamType.DIGITAL
            scope = ExamScope.GLOBAL
            durationMinutes = 30
            questionCount = 1
            status = ExamStatus.PUBLISHED
            createdBy = teacher.id
        })
        val question = questionRepository.save(ExamQuestionEntity().apply {
            examId = exam.id
            text = "2 + 2"
            qType = QuestionType.MCQ
            points = 4
            topic = "Algebra"
            orderIndex = 0
        })
        submissionRepository.save(ExamSubmissionEntity().apply {
            examId = exam.id
            userId = student.id
            score = 4
            totalPoints = 4
            percentage = 90
            grade = "A"
            correctCount = 1
            questionCount = 1
            timeTakenSeconds = 60
            submittedAt = now
            questionResults = objectMapper.writeValueAsString(
                listOf(mapOf("questionId" to question.id.toString(), "isCorrect" to true, "pointsEarned" to 4))
            )
        })
        submissionRepository.save(ExamSubmissionEntity().apply {
            examId = exam.id
            userId = peer.id
            score = 2
            totalPoints = 4
            percentage = 50
            grade = "D"
            correctCount = 0
            questionCount = 1
            timeTakenSeconds = 90
            submittedAt = now.minusSeconds(600)
            questionResults = objectMapper.writeValueAsString(
                listOf(mapOf("questionId" to question.id.toString(), "isCorrect" to false, "pointsEarned" to 0))
            )
        })
        contestRepository.save(ContestEntity().apply {
            title = "Dashboard Math Bee"
            subject = "Mathematics"
            grade = "Form 3"
            startTime = now.plus(2, ChronoUnit.DAYS)
            endTime = now.plus(2, ChronoUnit.DAYS).plus(1, ChronoUnit.HOURS)
            entryFee = 0
            prize = "Ksh 10,000"
            maxParticipants = 500
            lifecycle = ContestLifecycle.PUBLISHED
            createdBy = teacher.id
        })
        return Fixture(student, studentResp.sessionToken!!, clazz)
    }

    @Test
    fun `dashboard assignments, contests and quick actions`() {
        val f = fixture()
        val assignmentsBody = mockMvc.perform(
            get("/dashboard/assignments").header("Authorization", auth(f.studentToken))
        ).andExpect(status().isOk).andReturn().response.contentAsString
        val assignments = objectMapper.readValue(assignmentsBody, Array<AssignmentPayload>::class.java)
        check(assignments.size == 1)
        check(assignments.single().subject == "Mathematics")
        check(assignments.single().dueDate == "Tomorrow")
        check(!assignments.single().isOverdue)

        val contestsBody = mockMvc.perform(
            get("/dashboard/contests").header("Authorization", auth(f.studentToken))
        ).andExpect(status().isOk).andReturn().response.contentAsString
        val contests = objectMapper.readValue(contestsBody, Array<ContestPayload>::class.java)
        check(contests.size == 1)
        check(contests.single().status == "UPCOMING")
        check(contests.single().registeredCount == 0)
        check(!contests.single().isUserRegistered)

        val actionsBody = mockMvc.perform(
            get("/dashboard/quick-actions").header("Authorization", auth(f.studentToken))
        ).andExpect(status().isOk).andReturn().response.contentAsString
        val actions = objectMapper.readValue(actionsBody, Array<QuickActionPayload>::class.java)
        check(actions.size == 5)
        check(actions.any { it.route == "mock_interviews" })
    }

    @Test
    fun `dashboard insights are computed from live data`() {
        val f = fixture()
        val body = mockMvc.perform(
            get("/dashboard/insights").header("Authorization", auth(f.studentToken))
        ).andExpect(status().isOk).andReturn().response.contentAsString
        val insights = objectMapper.readValue(body, DashboardInsightsPayload::class.java)
        check(insights.focusZone.strugglingSubject == "Mathematics")
        check(insights.focusZone.recommendation.isNotEmpty())
        check(insights.nationalPulse.topSubject == "Mathematics")
        check(insights.teacherShoutout.teacherName == "Mr. Kiplagat")
        check(insights.teacherShoutout.dueDate == "Tomorrow")
        check(insights.peerComparison.percentile >= 50)
        check(insights.streakInfo.streakDays >= 1)
        check(insights.weeklyChallenge.current >= 1)
        check(insights.recentActivity.any { it.type == "EXAM_SUBMITTED" })
        check(insights.allBadges.isNotEmpty())
        check(insights.socialNotifications.isNotEmpty())
        check(insights.featuredContest.title == "Dashboard Math Bee")
    }

    @Test
    fun `dashboard requires authentication`() {
        mockMvc.perform(get("/dashboard/insights")).andExpect(status().isUnauthorized)
        mockMvc.perform(get("/dashboard/assignments")).andExpect(status().isUnauthorized)
    }
}
