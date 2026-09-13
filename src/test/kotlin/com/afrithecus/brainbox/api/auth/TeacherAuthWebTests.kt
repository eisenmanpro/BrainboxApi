package com.afrithecus.brainbox.api.auth

import com.afrithecus.brainbox.api.auth.web.AuthResponse
import com.afrithecus.brainbox.api.auth.web.CtcValidationPayload
import com.afrithecus.brainbox.api.auth.web.RotateCtcResponsePayload
import com.afrithecus.brainbox.api.auth.web.TeacherInfoUpdateRequest
import com.afrithecus.brainbox.api.auth.web.TeacherSignupRequest
import com.afrithecus.brainbox.api.auth.web.TeacherSignupResponse
import com.afrithecus.brainbox.api.auth.web.TeacherTransferRequest
import com.afrithecus.brainbox.api.auth.web.ValidateCtcRequest
import com.afrithecus.brainbox.api.identity.entity.SchoolEntity
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.model.Role
import com.afrithecus.brainbox.api.identity.model.SubRole
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.util.UUID

/** Teacher auth lifecycle + CTC contract (docs/ongoing/api_teacher_roster_changes.md). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class TeacherAuthWebTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val userRepository: UserRepository,
    @Autowired private val schoolRepository: SchoolRepository,
    @Autowired private val passwordEncoder: PasswordEncoder,
) {

    private lateinit var school: SchoolEntity
    private lateinit var otherSchool: SchoolEntity

    @BeforeEach
    fun setUp() {
        school = schoolRepository.save(SchoolEntity().apply { name = "Alliance High School"; isActive = true })
        otherSchool = schoolRepository.save(SchoolEntity().apply { name = "Moi Avenue School"; isActive = true })
    }

    private fun user(role: Role, name: String, phone: String, subRole: SubRole? = null, schoolId: UUID? = null): UserEntity {
        val entity = UserEntity()
        entity.phoneNumber = phone
        entity.email = phone + "@teacher.test"
        entity.passwordHash = passwordEncoder.encode("password123") ?: error("encode")
        entity.name = name
        entity.role = role
        entity.subRole = subRole
        entity.schoolId = schoolId ?: school.id
        entity.isActive = true
        entity.isVerified = true
        return userRepository.save(entity)
    }

    private fun token(identifier: String, password: String = "password123"): String {
        val login = mockMvc.perform(
            post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"identifier\":\"" + identifier + "\",\"password\":\"" + password + "\"}")
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return objectMapper.readValue(login, AuthResponse::class.java).sessionToken!!
    }

    private fun auth(token: String) = "Bearer " + token

    private fun teacherSignup(request: TeacherSignupRequest): TeacherSignupResponse {
        val response = mockMvc.perform(
            post("/auth/signup/teacher")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
        ).andReturn().response
        check(response.status == 200) { "teacher signup failed: " + response.status + " " + response.contentAsString }
        return objectMapper.readValue(response.contentAsString, TeacherSignupResponse::class.java)
    }

    @Test
    fun `teacher signup issues a CTC, starts pending and validates`() {
        val signup = teacherSignup(
            TeacherSignupRequest(
                name = "New Teacher",
                phoneNumber = "0755200001",
                password = "password123",
                schoolId = school.id.toString(),
                schoolName = school.name,
                grades = listOf("Grade 4"),
                className = "Grade 4 East",
                subjects = listOf("Mathematics"),
                tscNumber = "TSC-1",
            )
        )
        check(signup.success && signup.ctc.isNotBlank() && signup.ctcShareText.contains(signup.ctc))
        check(signup.sessionToken != null)
        check(signup.user.verificationStatus == "PENDING_VERIFICATION") { "got " + signup.user.verificationStatus }
        check(signup.user.teacherCode == signup.ctc)
        check(signup.user.className == "Grade 4 East")
        check(signup.user.teacherSubRole == "TEACHER")

        val validation = objectMapper.readValue(
            mockMvc.perform(
                post("/auth/validate-ctc").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"ctc\":\"" + signup.ctc + "\"}")
            ).andExpect(status().isOk).andReturn().response.contentAsString,
            CtcValidationPayload::class.java,
        )
        check(validation.isValid)
        check(validation.teacherName == "New Teacher")
        check(validation.className == "Grade 4 East")

        // Duplicate phone is rejected.
        mockMvc.perform(
            post("/auth/signup/teacher").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(TeacherSignupRequest("Dup", "0755200001", "password123", schoolId = school.id.toString(), schoolName = school.name)))
        ).andExpect(status().isConflict)
    }

    @Test
    fun `CTC rotates, freezes and unfreezes`() {
        val signup = teacherSignup(
            TeacherSignupRequest("Ctc Teacher", "0755200002", "password123", schoolId = school.id.toString(), schoolName = school.name, className = "Grade 5 West")
        )
        val t = token("0755200002")
        val rotated = objectMapper.readValue(
            mockMvc.perform(post("/auth/rotate-ctc").header("Authorization", auth(t)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            RotateCtcResponsePayload::class.java,
        )
        check(rotated.teacherCode != null && rotated.teacherCode != signup.ctc)

        val frozen = objectMapper.readValue(
            mockMvc.perform(post("/auth/ctc/freeze").header("Authorization", auth(t)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            AuthResponse::class.java,
        )
        check(frozen.user.ctcFrozen)
        val blocked = objectMapper.readValue(
            mockMvc.perform(
                post("/auth/validate-ctc").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"ctc\":\"" + rotated.teacherCode + "\"}")
            ).andExpect(status().isOk).andReturn().response.contentAsString,
            CtcValidationPayload::class.java,
        )
        check(!blocked.isValid && blocked.message!!.contains("frozen"))

        // A frozen code must also block a new student join server-side.
        mockMvc.perform(
            post("/auth/signup").contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        mapOf(
                            "name" to "Blocked Learner",
                            "phoneNumber" to "0755200999",
                            "password" to "password123",
                            "role" to "STUDENT",
                            "teacherCode" to rotated.teacherCode,
                            "schoolName" to school.name,
                        )
                    )
                )
        ).andExpect(status().isBadRequest)

        mockMvc.perform(post("/auth/ctc/unfreeze").header("Authorization", auth(t))).andExpect(status().isOk)
        val restored = objectMapper.readValue(
            mockMvc.perform(
                post("/auth/validate-ctc").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"ctc\":\"" + rotated.teacherCode + "\"}")
            ).andExpect(status().isOk).andReturn().response.contentAsString,
            CtcValidationPayload::class.java,
        )
        check(restored.isValid)
    }

    @Test
    fun `ICT admin approves, rejects, freezes and transfers teachers`() {
        val admin = user(Role.TEACHER, "ICT Admin", "0755200003", SubRole.ICT_ADMIN)
        val teacher = user(Role.TEACHER, "Pending Teacher", "0755200004")
        val second = user(Role.TEACHER, "Second Teacher", "0755200005")
        val adminToken = token(admin.phoneNumber!!)

        val approved = objectMapper.readValue(
            mockMvc.perform(post("/auth/teachers/" + teacher.id + "/approve").header("Authorization", auth(adminToken)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            AuthResponse::class.java,
        )
        check(approved.user.verificationStatus == "VERIFIED" && approved.user.isActive)

        val rejected = objectMapper.readValue(
            mockMvc.perform(post("/auth/teachers/" + second.id + "/reject").header("Authorization", auth(adminToken)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            AuthResponse::class.java,
        )
        check(rejected.user.verificationStatus == "REJECTED" && !rejected.user.isActive)
        mockMvc.perform(
            post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"identifier\":\"0755200005\",\"password\":\"password123\"}")
        ).andExpect(status().isUnauthorized)

        val frozen = objectMapper.readValue(
            mockMvc.perform(post("/auth/teachers/" + teacher.id + "/freeze").header("Authorization", auth(adminToken)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            AuthResponse::class.java,
        )
        check(frozen.user.verificationStatus == "FROZEN")
        mockMvc.perform(post("/auth/teachers/" + teacher.id + "/unfreeze").header("Authorization", auth(adminToken)))
            .andExpect(status().isOk)

        val transferred = objectMapper.readValue(
            mockMvc.perform(
                post("/auth/teachers/" + teacher.id + "/transfer").header("Authorization", auth(adminToken))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(TeacherTransferRequest(otherSchool.id.toString(), otherSchool.name)))
            ).andExpect(status().isOk).andReturn().response.contentAsString,
            AuthResponse::class.java,
        )
        check(transferred.user.schoolId == otherSchool.id.toString())
        check(transferred.user.teacherCode != null)

        // A plain teacher may not make decisions.
        val plain = user(Role.TEACHER, "Plain Teacher", "0755200006")
        mockMvc.perform(post("/auth/teachers/" + second.id + "/approve").header("Authorization", auth(token(plain.phoneNumber!!))))
            .andExpect(status().isForbidden)
    }

    @Test
    fun `class teacher approves and rejects their own students`() {
        val signup = teacherSignup(
            TeacherSignupRequest("Class Teacher", "0755200007", "password123", schoolId = school.id.toString(), schoolName = school.name, className = "Grade 4 East", grades = listOf("Grade 4"))
        )
        val teacherId = UUID.fromString(signup.user.id)
        val teacherToken = token("0755200007")

        val studentResponse = mockMvc.perform(
            post("/auth/signup").contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        mapOf(
                            "name" to "Pending Learner",
                            "phoneNumber" to "0755200008",
                            "password" to "password123",
                            "role" to "STUDENT",
                            "teacherCode" to signup.ctc,
                            "schoolName" to school.name,
                        )
                    )
                )
        ).andExpect(status().isOk).andReturn().response.contentAsString
        val auth = objectMapper.readValue(studentResponse, AuthResponse::class.java)
        check(auth.user.joinedTeacherId == teacherId.toString())
        check(auth.user.verificationStatus == "PENDING_VERIFICATION")

        val approved = objectMapper.readValue(
            mockMvc.perform(post("/auth/students/" + auth.user.id + "/approve").header("Authorization", auth(teacherToken)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            AuthResponse::class.java,
        )
        check(approved.user.verificationStatus == "VERIFIED")

        // A different teacher cannot decide for this student.
        val otherTeacher = user(Role.TEACHER, "Unrelated", "0755200009")
        mockMvc.perform(post("/auth/students/" + auth.user.id + "/reject").header("Authorization", auth(token(otherTeacher.phoneNumber!!))))
            .andExpect(status().isForbidden)
    }

    @Test
    fun `teacher info update and phone login`() {
        val signup = teacherSignup(
            TeacherSignupRequest("Editable Teacher", "0755200010", "password123", schoolId = school.id.toString(), schoolName = school.name, className = "Grade 6 North")
        )
        val t = token("0755200010")
        val updated = objectMapper.readValue(
            mockMvc.perform(
                put("/auth/teachers/" + signup.user.id).header("Authorization", auth(t))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        objectMapper.writeValueAsString(
                            TeacherInfoUpdateRequest(
                                name = "Renamed Teacher",
                                className = "Grade 6 South",
                                subjects = listOf("English"),
                                tscNumber = "TSC-9",
                                gradeLevels = listOf("Grade 6"),
                            )
                        )
                    )
            ).andExpect(status().isOk).andReturn().response.contentAsString,
            AuthResponse::class.java,
        )
        check(updated.user.name == "Renamed Teacher")
        check(updated.user.className == "Grade 6 South")
        check(updated.user.subjects == listOf("English"))
        check(updated.user.tscNumber == "TSC-9")

        mockMvc.perform(
            post("/auth/login/phone").contentType(MediaType.APPLICATION_JSON)
                .content("{\"phoneNumber\":\"0755200010\",\"password\":\"password123\"}")
        ).andExpect(status().isOk)
    }
}
