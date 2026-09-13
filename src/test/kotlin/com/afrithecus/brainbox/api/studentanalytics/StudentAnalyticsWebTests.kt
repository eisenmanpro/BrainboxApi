package com.afrithecus.brainbox.api.studentanalytics

import com.afrithecus.brainbox.api.achievements.web.UserAchievementsPayload
import com.afrithecus.brainbox.api.attendance.entity.AttendanceRecordEntity
import com.afrithecus.brainbox.api.attendance.model.AttendanceStatus
import com.afrithecus.brainbox.api.attendance.repository.AttendanceRecordRepository
import com.afrithecus.brainbox.api.auth.web.AuthResponse
import com.afrithecus.brainbox.api.classes.entity.ClassMembershipEntity
import com.afrithecus.brainbox.api.classes.entity.TeacherClassEntity
import com.afrithecus.brainbox.api.classes.repository.ClassMembershipRepository
import com.afrithecus.brainbox.api.classes.repository.TeacherClassRepository
import com.afrithecus.brainbox.api.exams.entity.ExamEntity
import com.afrithecus.brainbox.api.exams.entity.ExamSubmissionEntity
import com.afrithecus.brainbox.api.exams.repository.ExamRepository
import com.afrithecus.brainbox.api.exams.repository.ExamSubmissionRepository
import com.afrithecus.brainbox.api.identity.entity.SchoolEntity
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.model.Role
import com.afrithecus.brainbox.api.identity.repository.SchoolRepository
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import com.afrithecus.brainbox.api.studentanalytics.web.ClassComparisonsPayload
import com.afrithecus.brainbox.api.studentanalytics.web.EngagementScorePayload
import com.afrithecus.brainbox.api.studentanalytics.web.StudentAnalyticsPayload
import com.afrithecus.brainbox.api.studentanalytics.web.StudentAttendancePayload
import com.afrithecus.brainbox.api.studentanalytics.web.StudentHomeworkPayload
import com.afrithecus.brainbox.api.studentanalytics.web.SubjectPerformancePayload
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
import java.time.LocalDate
import java.util.UUID

/** Teacher student analytics (doc 04 section 14). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class StudentAnalyticsWebTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val userRepository: UserRepository,
    @Autowired private val schoolRepository: SchoolRepository,
    @Autowired private val classRepository: TeacherClassRepository,
    @Autowired private val membershipRepository: ClassMembershipRepository,
    @Autowired private val examRepository: ExamRepository,
    @Autowired private val submissionRepository: ExamSubmissionRepository,
    @Autowired private val attendanceRepository: AttendanceRecordRepository,
    @Autowired private val passwordEncoder: PasswordEncoder,
) {
    private fun user(role: Role, name: String, phone: String, schoolId: UUID): UserEntity =
        userRepository.save(UserEntity().apply {
            phoneNumber = phone
            email = phone + "@analytics.test"
            passwordHash = passwordEncoder.encode("password123") ?: error("encode")
            this.name = name
            this.role = role
            this.schoolId = schoolId
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
    fun `student analytics aggregate and components`() {
        val school = SchoolEntity().apply {
            name = "Alliance High School"
            isActive = true
        }
        schoolRepository.save(school)
        val teacher = user(Role.TEACHER, "Class Teacher", "0755100001", school.id)
        val other = user(Role.TEACHER, "Other Teacher", "0755100002", school.id)
        val student = user(Role.STUDENT, "Alice Learner", "0755100003", school.id)
        val clazz = classRepository.save(TeacherClassEntity().apply {
            teacherUserId = teacher.id
            this.schoolId = school.id
            name = "Grade 4 South"
            gradeLevel = "Grade 4"
            subject = "Mathematics"
            isActive = true
        })
        membershipRepository.save(ClassMembershipEntity().apply {
            classId = clazz.id
            studentId = student.id
        })
        val exam = examRepository.save(ExamEntity().apply {
            title = "Mid-Term"
            subject = "Mathematics"
            durationMinutes = 60
            createdBy = teacher.id
        })
        submissionRepository.save(ExamSubmissionEntity().apply {
            examId = exam.id
            userId = student.id
            score = 80
            totalPoints = 100
            percentage = 80
            submittedAt = Instant.now()
        })
        attendanceRepository.save(AttendanceRecordEntity().apply {
            classId = clazz.id
            studentId = student.id
            attendanceDate = LocalDate.now()
            status = AttendanceStatus.PRESENT
        })
        val t = token(teacher)

        val analytics = objectMapper.readValue(
            mockMvc.perform(get("/teacher/analytics/student/" + student.id).header("Authorization", auth(t)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            StudentAnalyticsPayload::class.java,
        )
        check(analytics.studentName == "Alice Learner")
        check(analytics.subjectPerformance.any { it.subject == "Mathematics" && it.score == 80.0 })
        check(analytics.attendance.size == 1)
        check(analytics.homeworkHistory.isEmpty())
        check(analytics.quickStats.avgScore == 80)
        check(analytics.quickStats.attendancePercentage == 100.0)
        check(analytics.classComparisons != null)

        val performance = objectMapper.readValue(
            mockMvc.perform(get("/teacher/analytics/student/" + student.id + "/subject-performance")
                .header("Authorization", auth(t)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            Array<SubjectPerformancePayload>::class.java,
        )
        check(performance.size == 1)

        val attendance = objectMapper.readValue(
            mockMvc.perform(get("/teacher/analytics/student/" + student.id + "/attendance?startDate=0&endDate=0")
                .header("Authorization", auth(t)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            Array<StudentAttendancePayload>::class.java,
        )
        check(attendance.single().status == "PRESENT")

        val homework = objectMapper.readValue(
            mockMvc.perform(get("/teacher/analytics/student/" + student.id + "/homework")
                .header("Authorization", auth(t)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            Array<StudentHomeworkPayload>::class.java,
        )
        check(homework.isEmpty())

        val engagement = objectMapper.readValue(
            mockMvc.perform(get("/teacher/analytics/student/" + student.id + "/engagement")
                .header("Authorization", auth(t)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            EngagementScorePayload::class.java,
        )
        check(engagement.weekly == 0)

        val comparisons = objectMapper.readValue(
            mockMvc.perform(get("/teacher/analytics/class/" + clazz.id + "/comparisons")
                .header("Authorization", auth(t)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            ClassComparisonsPayload::class.java,
        )
        check(comparisons.classAverageScore == 80.0)

        val achievements = objectMapper.readValue(
            mockMvc.perform(get("/teacher/analytics/student/" + student.id + "/achievements")
                .header("Authorization", auth(t)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            UserAchievementsPayload::class.java,
        )
        check(achievements.userId == student.id.toString())

        mockMvc.perform(get("/teacher/analytics/student/" + student.id).header("Authorization", auth(token(other))))
            .andExpect(status().isForbidden)
    }
}
