package com.afrithecus.brainbox.api.gradebook.web

import com.afrithecus.brainbox.api.auth.web.AuthResponse
import com.afrithecus.brainbox.api.classes.entity.TeacherClassEntity
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
import org.junit.jupiter.api.BeforeEach
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

/**
 * GB-04: the web gradebook needs a class-scoped exam-submission read so exam marks
 * appear there exactly as Android already merges them from Room.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class GradebookExamSubmissionsWebTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val userRepository: UserRepository,
    @Autowired private val schoolRepository: SchoolRepository,
    @Autowired private val classRepository: TeacherClassRepository,
    @Autowired private val examRepository: ExamRepository,
    @Autowired private val submissionRepository: ExamSubmissionRepository,
    @Autowired private val passwordEncoder: PasswordEncoder,
) {
    private lateinit var school: SchoolEntity
    private lateinit var teacher: UserEntity
    private lateinit var otherTeacher: UserEntity
    private lateinit var clazz: TeacherClassEntity
    private lateinit var student: UserEntity

    private fun account(role: Role, phone: String): UserEntity = userRepository.save(UserEntity().apply {
        name = "Gradebook " + phone
        phoneNumber = phone
        email = phone + "@gradebook.test"
        passwordHash = passwordEncoder.encode("password123") ?: error("encode")
        this.role = role
        schoolId = school.id
        isActive = true
        isVerified = true
    })

    private fun token(user: UserEntity): String {
        val body = "{\"identifier\":\"" + user.email + "\",\"password\":\"password123\"}"
        val response = mockMvc.perform(
            post("/auth/login").contentType(MediaType.APPLICATION_JSON).content(body)
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return objectMapper.readValue(response, AuthResponse::class.java).sessionToken!!
    }

    @BeforeEach
    fun setUp() {
        school = schoolRepository.save(SchoolEntity().apply { name = "Gradebook Test School"; isActive = true })
        teacher = account(Role.TEACHER, "0755700001")
        otherTeacher = account(Role.TEACHER, "0755700002")
        student = account(Role.STUDENT, "0755700003")
        clazz = classRepository.save(TeacherClassEntity().apply {
            teacherUserId = teacher.id
            schoolId = school.id
            name = "5A"
            gradeLevel = "Grade 5"
            subject = "Mathematics"
            isActive = true
        })
    }

    @Test
    fun `class-scoped exam submissions are returned for the class owner`() {
        val exam = examRepository.save(ExamEntity().apply {
            title = "Term 1 Maths"
            subject = "Mathematics"
            classId = clazz.id
            createdBy = teacher.id
            schoolId = school.id
            gradeLevel = 5
            totalPoints = 100
            term = "TERM_1"
        })
        submissionRepository.save(ExamSubmissionEntity().apply {
            examId = exam.id
            userId = student.id
            score = 82
            totalPoints = 100
            percentage = 82
            submittedAt = Instant.now()
        })

        val rows = objectMapper.readValue(
            mockMvc.perform(
                get("/teacher/classes/" + clazz.id + "/exam-submissions")
                    .header("Authorization", "Bearer " + token(teacher))
            ).andExpect(status().isOk).andReturn().response.contentAsString,
            Array<ExamGradebookEntryPayload>::class.java,
        )
        check(rows.size == 1)
        check(rows[0].studentId == student.id.toString())
        check(rows[0].rawScore == 82)
        check(rows[0].term == "TERM_1")
        check(rows[0].examTitle == "Term 1 Maths")

        mockMvc.perform(
            get("/teacher/classes/" + clazz.id + "/exam-submissions")
                .header("Authorization", "Bearer " + token(otherTeacher))
        ).andExpect(status().isForbidden)
    }
}
