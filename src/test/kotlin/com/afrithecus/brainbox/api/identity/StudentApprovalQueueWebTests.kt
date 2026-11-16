package com.afrithecus.brainbox.api.identity

import com.afrithecus.brainbox.api.auth.web.AuthResponse
import com.afrithecus.brainbox.api.classes.entity.TeacherClassEntity
import com.afrithecus.brainbox.api.classes.repository.TeacherClassRepository
import com.afrithecus.brainbox.api.identity.entity.SchoolEntity
import com.afrithecus.brainbox.api.identity.entity.TeacherCodeEntity
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.model.AccountStatus
import com.afrithecus.brainbox.api.identity.model.Role
import com.afrithecus.brainbox.api.identity.model.SubRole
import com.afrithecus.brainbox.api.identity.repository.SchoolRepository
import com.afrithecus.brainbox.api.identity.repository.TeacherCodeRepository
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import com.afrithecus.brainbox.api.identity.web.PendingStudentPayload
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
import java.util.UUID

/**
 * The class teacher's learner approval queue reads
 * (`GET /auth/students/pending|rejected?teacherCode=`) — the missing half of the
 * approve/reject endpoints the roster screen already calls.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class StudentApprovalQueueWebTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val userRepository: UserRepository,
    @Autowired private val schoolRepository: SchoolRepository,
    @Autowired private val classRepository: TeacherClassRepository,
    @Autowired private val teacherCodeRepository: TeacherCodeRepository,
    @Autowired private val passwordEncoder: PasswordEncoder,
) {

    private lateinit var school: SchoolEntity
    private lateinit var teacher: UserEntity
    private lateinit var code: TeacherCodeEntity

    private fun user(
        role: Role,
        name: String,
        phone: String,
        schoolId: UUID,
        subRole: SubRole? = null,
        status: AccountStatus = AccountStatus.VERIFIED,
    ): UserEntity = userRepository.save(UserEntity().apply {
        phoneNumber = phone
        email = phone + "@studentqueue.test"
        passwordHash = passwordEncoder.encode("password123") ?: error("encode")
        this.name = name
        this.role = role
        this.subRole = subRole
        this.schoolId = schoolId
        isActive = status != AccountStatus.REJECTED
        isVerified = status == AccountStatus.VERIFIED
        verificationStatus = status
    })

    private fun learner(name: String, phone: String, status: AccountStatus): UserEntity =
        userRepository.save(UserEntity().apply {
            this.name = name
            this.phoneNumber = phone
            role = Role.STUDENT
            schoolId = school.id
            credentialKind = "PIN"
            accountKind = com.afrithecus.brainbox.api.identity.model.AccountKind.FULL
            joinedTeacherId = teacher.id
            referredByTeacherCode = code.code
            studentAdmissionNumber = "ADM-" + phone.takeLast(4)
            isActive = true
            isVerified = status == AccountStatus.VERIFIED
            verificationStatus = status
        })

    private fun token(entity: UserEntity): String {
        val body = mockMvc.perform(
            post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"identifier\":\"" + entity.phoneNumber + "\",\"password\":\"password123\"}")
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return objectMapper.readValue(body, AuthResponse::class.java).sessionToken!!
    }

    private fun queue(path: String, token: String): List<PendingStudentPayload> {
        val body = mockMvc.perform(get(path).header("Authorization", "Bearer " + token))
            .andExpect(status().isOk).andReturn().response.contentAsString
        return objectMapper.readValue(body, Array<PendingStudentPayload>::class.java).toList()
    }

    @BeforeEach
    fun setUp() {
        school = schoolRepository.save(SchoolEntity().apply { name = "Alliance High School"; isActive = true })
        teacher = user(Role.TEACHER, "Class Teacher", "0777700001", school.id, SubRole.CTEACHER)
        code = teacherCodeRepository.save(TeacherCodeEntity().apply {
            code = "CTCLIST1"
            teacherUserId = teacher.id
            schoolId = school.id
        })
        classRepository.save(TeacherClassEntity().apply {
            teacherUserId = teacher.id
            schoolId = school.id
            name = "Grade 7 West"
            gradeLevel = "Grade 7"
            subject = "Mathematics"
            isActive = true
        })
    }

    @Test
    fun `pending and rejected learners are listed for the code owner with their class grade`() {
        learner("Alice Pending", "0777700002", AccountStatus.PENDING_VERIFICATION)
        learner("Bob Rejected", "0777700003", AccountStatus.REJECTED)
        learner("Cara Verified", "0777700004", AccountStatus.VERIFIED)
        val t = token(teacher)

        val pending = queue("/auth/students/pending?teacherCode=" + code.code, t)
        check(pending.size == 1 && pending.single().name == "Alice Pending") { "pending=" + pending }
        check(pending.single().grade == "Grade 7")
        check(pending.single().referredByTeacherCode == code.code)
        check(pending.single().admissionNumber != null && pending.single().joinedAt > 0)

        val rejected = queue("/auth/students/rejected?teacherCode=" + code.code, t)
        check(rejected.size == 1 && rejected.single().name == "Bob Rejected")

        // Omitting the code falls back to the caller's own code.
        check(queue("/auth/students/pending", t).size == 1)
    }

    @Test
    fun `another teacher cannot read this queue by guessing the code`() {
        learner("Alice Pending", "0777700005", AccountStatus.PENDING_VERIFICATION)
        val intruder = user(Role.TEACHER, "Intruder Teacher", "0777700006", school.id, SubRole.CTEACHER)
        mockMvc.perform(
            get("/auth/students/pending?teacherCode=" + code.code)
                .header("Authorization", "Bearer " + token(intruder))
        ).andExpect(status().isForbidden)
    }

    @Test
    fun `the school ICT admin can read any class queue in the school`() {
        learner("Alice Pending", "0777700007", AccountStatus.PENDING_VERIFICATION)
        val admin = user(Role.TEACHER, "ICT Admin", "0777700008", school.id, SubRole.ICT_ADMIN)
        val rows = queue("/auth/students/pending?teacherCode=" + code.code, token(admin))
        check(rows.size == 1 && rows.single().name == "Alice Pending")
    }
}
