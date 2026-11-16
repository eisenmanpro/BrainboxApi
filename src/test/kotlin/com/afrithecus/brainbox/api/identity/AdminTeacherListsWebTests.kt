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
import com.afrithecus.brainbox.api.identity.web.ManagedTeacherPayload
import com.afrithecus.brainbox.api.identity.web.PendingTeacherPayload
import com.afrithecus.brainbox.api.teacher.entity.TeacherSettingsEntity
import com.afrithecus.brainbox.api.teacher.repository.TeacherSettingsRepository
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
 * The ICT admin's teacher management tabs (`GET /admin/schools/{schoolId}/pending-teachers`,
 * `/rejected-teachers`, `/managed-teachers`) — the lists the web's user-management screen
 * reads but the API never exposed (ADM-08).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AdminTeacherListsWebTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val userRepository: UserRepository,
    @Autowired private val schoolRepository: SchoolRepository,
    @Autowired private val classRepository: TeacherClassRepository,
    @Autowired private val settingsRepository: TeacherSettingsRepository,
    @Autowired private val teacherCodeRepository: TeacherCodeRepository,
    @Autowired private val passwordEncoder: PasswordEncoder,
) {

    private lateinit var school: SchoolEntity
    private lateinit var otherSchool: SchoolEntity
    private lateinit var admin: UserEntity

    private fun user(
        role: Role,
        name: String,
        phone: String,
        schoolId: UUID,
        subRole: SubRole? = null,
        grade: String? = null,
        status: AccountStatus = AccountStatus.VERIFIED,
    ): UserEntity = userRepository.save(UserEntity().apply {
        phoneNumber = phone
        email = phone + "@teacherlist.test"
        passwordHash = passwordEncoder.encode("password123") ?: error("encode")
        this.name = name
        this.role = role
        this.subRole = subRole
        this.schoolId = schoolId
        gradeLevel = grade
        isActive = status != AccountStatus.REJECTED && status != AccountStatus.FROZEN
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

    private fun auth(token: String) = "Bearer " + token

    private fun <T> list(path: String, token: String, type: Class<Array<T>>): List<T> {
        val body = mockMvc.perform(get(path).header("Authorization", auth(token)))
            .andExpect(status().isOk).andReturn().response.contentAsString
        return objectMapper.readValue(body, type).toList()
    }

    @BeforeEach
    fun setUp() {
        school = schoolRepository.save(SchoolEntity().apply { name = "Alliance High School"; isActive = true })
        otherSchool = schoolRepository.save(SchoolEntity().apply { name = "Moi Avenue School"; isActive = true })
        admin = user(Role.TEACHER, "ICT Admin", "0766600001", school.id, SubRole.ICT_ADMIN)
    }

    @Test
    fun `pending rejected and managed teacher tabs read their own status`() {
        val pending = user(
            Role.TEACHER, "Pending Teacher", "0766600002", school.id, SubRole.CTEACHER,
            grade = "Grade 7", status = AccountStatus.PENDING_VERIFICATION,
        )
        val rejected = user(
            Role.TEACHER, "Rejected Teacher", "0766600003", school.id, SubRole.CTEACHER,
            status = AccountStatus.REJECTED,
        )
        val frozen = user(
            Role.TEACHER, "Frozen Teacher", "0766600004", school.id, SubRole.GRADE_COORDINATOR,
            grade = "Grade 8", status = AccountStatus.FROZEN,
        )
        val active = user(Role.TEACHER, "Active Teacher", "0766600005", school.id, grade = "Grade 7")

        // The pending row must carry the catalogue a review needs: class, grades, subjects, TSC.
        classRepository.save(TeacherClassEntity().apply {
            teacherUserId = pending.id
            schoolId = school.id
            name = "Grade 7 West"
            gradeLevel = "Grade 7"
            subject = "Mathematics"
            isActive = true
        })
        settingsRepository.save(TeacherSettingsEntity().apply {
            teacherId = pending.id
            subjectsTaught = "[\"Mathematics\",\"Science\"]"
            gradesTaught = "[\"Grade 7\"]"
            primaryClassName = "Grade 7 West"
            tscNumber = "TSC-1234"
        })
        teacherCodeRepository.save(TeacherCodeEntity().apply {
            code = "CTCPEND1"
            teacherUserId = pending.id
            schoolId = school.id
        })

        val t = token(admin)
        val pendingRows = list(
            "/admin/schools/" + school.id + "/pending-teachers", t, Array<PendingTeacherPayload>::class.java,
        )
        check(pendingRows.size == 1) { "expected one pending teacher, got " + pendingRows.size }
        check(pendingRows.single().id == pending.id.toString())
        check(pendingRows.single().className == "Grade 7 West")
        check(pendingRows.single().gradeLevels == listOf("Grade 7"))
        check(pendingRows.single().subjects == listOf("Mathematics", "Science"))
        check(pendingRows.single().tscNumber == "TSC-1234")
        check(pendingRows.single().teacherCode == "CTCPEND1")
        check(pendingRows.single().joinedAt > 0)

        val rejectedRows = list(
            "/admin/schools/" + school.id + "/rejected-teachers", t, Array<PendingTeacherPayload>::class.java,
        )
        check(rejectedRows.size == 1 && rejectedRows.single().id == rejected.id.toString())

        val managedRows = list(
            "/admin/schools/" + school.id + "/managed-teachers", t, Array<ManagedTeacherPayload>::class.java,
        )
        val ids = managedRows.map { it.id }.toSet()
        check(ids == setOf(active.id.toString(), frozen.id.toString())) { "managed ids were " + ids }
        val frozenRow = managedRows.single { it.id == frozen.id.toString() }
        check(frozenRow.isFrozen && frozenRow.verificationStatus == "FROZEN")
        check(frozenRow.subRole == "GRADE_COORDINATOR")
        check(managedRows.none { it.id == pending.id.toString() || it.id == rejected.id.toString() })
    }

    @Test
    fun `another school's admin cannot read this school's teacher lists`() {
        val foreignAdmin = user(Role.TEACHER, "Other Admin", "0766600006", otherSchool.id, SubRole.ICT_ADMIN)
        mockMvc.perform(
            get("/admin/schools/" + school.id + "/pending-teachers").header("Authorization", auth(token(foreignAdmin)))
        ).andExpect(status().isForbidden)
    }

    @Test
    fun `a plain teacher cannot read the admin teacher lists`() {
        val teacher = user(Role.TEACHER, "Plain Teacher", "0766600007", school.id, SubRole.CTEACHER, grade = "Grade 7")
        mockMvc.perform(
            get("/admin/schools/" + school.id + "/managed-teachers").header("Authorization", auth(token(teacher)))
        ).andExpect(status().isForbidden)
    }
}
