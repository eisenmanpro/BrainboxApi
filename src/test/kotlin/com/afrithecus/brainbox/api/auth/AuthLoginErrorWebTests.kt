package com.afrithecus.brainbox.api.auth

import com.afrithecus.brainbox.api.auth.web.TeacherSignupRequest
import com.afrithecus.brainbox.api.identity.entity.SchoolConfigEntity
import com.afrithecus.brainbox.api.identity.entity.SchoolEntity
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.model.AccountStatus
import com.afrithecus.brainbox.api.identity.model.Role
import com.afrithecus.brainbox.api.identity.model.SubRole
import com.afrithecus.brainbox.api.identity.repository.SchoolConfigRepository
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper

/**
 * Findings "# AUTH": login failures are distinguishable for the client (unknown account vs
 * wrong password vs inactive account) without leaking anything beyond that, and a coordinator
 * signup into a taken grade tells the user to contact their school admin instead of implying
 * who holds the grade.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AuthLoginErrorWebTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val userRepository: UserRepository,
    @Autowired private val schoolRepository: SchoolRepository,
    @Autowired private val configRepository: SchoolConfigRepository,
    @Autowired private val passwordEncoder: PasswordEncoder,
) {
    private lateinit var school: SchoolEntity

    private fun user(
        name: String,
        phone: String,
        role: Role = Role.TEACHER,
        subRole: SubRole? = null,
        grade: String? = null,
        status: AccountStatus = AccountStatus.VERIFIED,
    ): UserEntity = userRepository.save(UserEntity().apply {
        this.name = name
        phoneNumber = phone
        email = phone + "@autherror.test"
        passwordHash = passwordEncoder.encode("password123") ?: error("encode")
        this.role = role
        this.subRole = subRole
        schoolId = this@AuthLoginErrorWebTests.school.id
        gradeLevel = grade
        isActive = status != AccountStatus.REJECTED
        isVerified = status == AccountStatus.VERIFIED
        verificationStatus = status
    })

    @BeforeEach
    fun setUp() {
        school = schoolRepository.save(SchoolEntity().apply { name = "Alliance High School"; isActive = true })
        configRepository.save(SchoolConfigEntity().apply {
            schoolId = school.id
            schoolName = school.name
            classes = "[{\"name\":\"Lion\",\"gradeLevel\":\"Grade 5\"}]"
        })
    }

    private fun login(identifier: String, password: String) = mockMvc.perform(
        post("/auth/login").contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(mapOf("identifier" to identifier, "password" to password))),
    )

    @Test
    fun `login distinguishes unknown account wrong password and an inactive account`() {
        login("0799900001", "password123")
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.error").value("ACCOUNT_NOT_FOUND"))
            .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("No account matches")))

        user("Real Teacher", "0799900002")
        login("0799900002", "wrong-password")
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.error").value("INVALID_CREDENTIALS"))
            .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("not correct")))

        val rejected = user("Rejected Teacher", "0799900003", status = AccountStatus.REJECTED)
        login(rejected.phoneNumber!!, "password123")
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.error").value("ACCOUNT_INACTIVE"))
            .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Contact your school admin")))
    }

    @Test
    fun `a coordinator signup into a taken grade asks the user to contact the school admin`() {
        user("Existing Coordinator", "0799900010", subRole = SubRole.GRADE_COORDINATOR, grade = "Grade 5")
        val request = TeacherSignupRequest(
            name = "New Coordinator",
            phoneNumber = "0799900011",
            password = "password123",
            email = "new.coordinator@autherror.test",
            schoolId = school.id.toString(),
            subRole = "GRADE_COORDINATOR",
            grades = listOf("Grade 5"),
            className = "Lion",
            subjects = listOf("Mathematics"),
        )
        val response = mockMvc.perform(
            post("/auth/signup/teacher").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)),
        ).andExpect(status().isConflict).andReturn().response.contentAsString
        check(response.contains("contact your school admin", ignoreCase = true)) { "message=" + response }
        check(!response.contains("already has a grade coordinator", ignoreCase = true)) { "leaked: " + response }
    }
}
