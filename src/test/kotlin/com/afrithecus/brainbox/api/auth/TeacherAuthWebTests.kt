package com.afrithecus.brainbox.api.auth

import com.afrithecus.brainbox.api.auth.web.AuthResponse
import com.afrithecus.brainbox.api.auth.web.CtcValidationPayload
import com.afrithecus.brainbox.api.auth.web.RotateCtcResponsePayload
import com.afrithecus.brainbox.api.auth.web.TeacherInfoUpdateRequest
import com.afrithecus.brainbox.api.auth.web.TeacherSignupRequest
import com.afrithecus.brainbox.api.auth.web.TeacherSignupResponse
import com.afrithecus.brainbox.api.auth.web.TeacherTransferRequest
import com.afrithecus.brainbox.api.identity.entity.SchoolConfigEntity
import com.afrithecus.brainbox.api.identity.entity.SchoolEntity
import com.afrithecus.brainbox.api.identity.entity.UserEntity
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
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
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
@Import(CapturingEmailConfig::class)
class TeacherAuthWebTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val userRepository: UserRepository,
    @Autowired private val schoolRepository: SchoolRepository,
    @Autowired private val schoolConfigRepository: SchoolConfigRepository,
    @Autowired private val passwordEncoder: PasswordEncoder,
    @Autowired private val sender: CapturingEmailSender,
) {

    private lateinit var school: SchoolEntity
    private lateinit var otherSchool: SchoolEntity

    @BeforeEach
    fun setUp() {
        school = schoolRepository.save(SchoolEntity().apply { name = "Alliance High School"; isActive = true })
        otherSchool = schoolRepository.save(SchoolEntity().apply { name = "Moi Avenue School"; isActive = true })
        schoolConfigRepository.save(
            SchoolConfigEntity().apply {
                schoolId = school.id
                schoolName = school.name
                classes = """[{"name":"Grade 4 East","gradeLevel":"Grade 4"},{"name":"Grade 5 West","gradeLevel":"Grade 5"},{"name":"Grade 6 North","gradeLevel":"Grade 6"},{"name":"Grade 6 South","gradeLevel":"Grade 6"}]"""
            }
        )
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

    /** Confirms the signup email and returns the token the verify step issues. */
    private fun verifyEmail(email: String): AuthResponse {
        val response = mockMvc.perform(
            post("/auth/email/verify").contentType(MediaType.APPLICATION_JSON)
                .content("{\"identifier\":\"" + email + "\",\"code\":\"" + sender.codeFor(email) + "\"}")
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return objectMapper.readValue(response, AuthResponse::class.java)
    }

    private fun validateCtc(code: String): CtcValidationPayload = objectMapper.readValue(
        mockMvc.perform(
            post("/auth/validate-ctc").contentType(MediaType.APPLICATION_JSON)
                .content("{\"ctc\":\"" + code + "\"}")
        ).andExpect(status().isOk).andReturn().response.contentAsString,
        CtcValidationPayload::class.java,
    )

    @Test
    fun `teacher signup issues a pending CTC that only works after email and school approval`() {
        val signup = teacherSignup(
            TeacherSignupRequest(
                name = "New Teacher",
                phoneNumber = "0755200001",
                password = "password123",
                email = "new.teacher@brainbox.test",
                schoolId = school.id.toString(),
                subRole = "CTEACHER",
                grades = listOf("Grade 4"),
                className = "Grade 4 East",
                subjects = listOf("Mathematics"),
                tscNumber = "TSC-1",
            )
        )
        check(signup.success && signup.ctc.isNotBlank() && signup.ctcShareText.contains(signup.ctc))
        check(signup.sessionToken == null) { "an unverified signup must not get a session" }
        check(signup.emailVerificationRequired)
        check(signup.user.verificationStatus == "PENDING_VERIFICATION") { "got " + signup.user.verificationStatus }
        check(signup.user.teacherCode == signup.ctc)
        check(signup.user.className == "Grade 4 East")
        check(signup.user.teacherSubRole == "CTEACHER")

        // The code exists so it can be shared after approval, but it must not work while pending.
        val pending = validateCtc(signup.ctc)
        check(!pending.isValid && pending.message!!.contains("awaiting approval")) { "got " + pending.message }

        val verified = verifyEmail("new.teacher@brainbox.test")
        check(verified.sessionToken != null)
        // Email confirmed, but no ICT admin has approved yet: the account still has no data access.
        val blocked = mockMvc.perform(
            get("/profile/settings/" + verified.user.id).header("Authorization", auth(verified.sessionToken!!))
        ).andExpect(status().isForbidden).andReturn().response.contentAsString
        check(blocked.contains("ACCOUNT_PENDING")) { "got " + blocked }

        // The school's ICT admin approves; the code becomes usable.
        val admin = user(Role.TEACHER, "ICT Admin", "0755200098", SubRole.ICT_ADMIN)
        mockMvc.perform(post("/auth/teachers/" + signup.user.id + "/approve").header("Authorization", auth(token(admin.phoneNumber!!))))
            .andExpect(status().isOk)
        val approved = validateCtc(signup.ctc)
        check(approved.isValid && approved.teacherName == "New Teacher" && approved.className == "Grade 4 East")

        // Duplicate phone and duplicate email are rejected.
        mockMvc.perform(
            post("/auth/signup/teacher").contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        TeacherSignupRequest("Dup", "0755200001", "password123", email = "dup.phone@brainbox.test", schoolId = school.id.toString(), grades = listOf("Grade 4"))
                    )
                )
        ).andExpect(status().isConflict)
        mockMvc.perform(
            post("/auth/signup/teacher").contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        TeacherSignupRequest("Dup Email", "0755200097", "password123", email = "NEW.TEACHER@brainbox.test", schoolId = school.id.toString(), grades = listOf("Grade 4"))
                    )
                )
        ).andExpect(status().isConflict)
    }

    @Test
    fun `CTC rotates, freezes and unfreezes`() {
        user(Role.TEACHER, "Ctc Teacher", "0755200002", SubRole.CTEACHER)
        val t = token("0755200002")
        val rotated = objectMapper.readValue(
            mockMvc.perform(post("/auth/rotate-ctc").header("Authorization", auth(t)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            RotateCtcResponsePayload::class.java,
        )
        check(rotated.teacherCode != null)
        check(validateCtc(rotated.teacherCode!!).isValid)

        val frozen = objectMapper.readValue(
            mockMvc.perform(post("/auth/ctc/freeze").header("Authorization", auth(t)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            AuthResponse::class.java,
        )
        check(frozen.user.ctcFrozen)
        val blocked = validateCtc(rotated.teacherCode)
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
        check(validateCtc(rotated.teacherCode).isValid)
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
        val teacher = user(Role.TEACHER, "Class Teacher", "0755200007", SubRole.CTEACHER)
        val teacherToken = token("0755200007")
        val code = objectMapper.readValue(
            mockMvc.perform(post("/auth/rotate-ctc").header("Authorization", auth(teacherToken)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            RotateCtcResponsePayload::class.java,
        ).teacherCode!!

        val studentResponse = mockMvc.perform(
            post("/auth/signup").contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        mapOf(
                            "name" to "Pending Learner",
                            "phoneNumber" to "0755200008",
                            "password" to "password123",
                            "role" to "STUDENT",
                            "teacherCode" to code,
                            "schoolName" to school.name,
                        )
                    )
                )
        ).andExpect(status().isOk).andReturn().response.contentAsString
        val auth = objectMapper.readValue(studentResponse, AuthResponse::class.java)
        check(auth.user.joinedTeacherId == teacher.id.toString())
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
    fun `teacher info update by the school admin and phone login`() {
        val teacher = user(Role.TEACHER, "Editable Teacher", "0755200010")
        val t = token("0755200010")
        // N3: self-service through the admin teacher route may change identity fields only.
        mockMvc.perform(
            put("/auth/teachers/" + teacher.id).header("Authorization", auth(t))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(TeacherInfoUpdateRequest(name = "Renamed Teacher"))),
        ).andExpect(status().isOk)
        mockMvc.perform(
            put("/auth/teachers/" + teacher.id).header("Authorization", auth(t))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(TeacherInfoUpdateRequest(subjects = listOf("English")))),
        ).andExpect(status().isConflict)

        // The school admin (the approver) may change governed fields directly.
        val admin = user(Role.TEACHER, "ICT Admin", "0755200099", SubRole.ICT_ADMIN, school.id)
        val adminToken = token(admin.phoneNumber!!)
        val updated = objectMapper.readValue(
            mockMvc.perform(
                put("/auth/teachers/" + teacher.id).header("Authorization", auth(adminToken))
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
