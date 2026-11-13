package com.afrithecus.brainbox.api.identity

import com.afrithecus.brainbox.api.auth.web.AuthResponse
import com.afrithecus.brainbox.api.classes.entity.TeacherClassEntity
import com.afrithecus.brainbox.api.classes.repository.TeacherClassRepository
import com.afrithecus.brainbox.api.identity.entity.SchoolEntity
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.model.AccountStatus
import com.afrithecus.brainbox.api.identity.model.Role
import com.afrithecus.brainbox.api.identity.model.SubRole
import com.afrithecus.brainbox.api.identity.repository.SchoolRepository
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import com.afrithecus.brainbox.api.identity.web.AuditLogEntryPayload
import com.afrithecus.brainbox.api.identity.web.CoordinatorAssignmentPayload
import com.afrithecus.brainbox.api.identity.web.CoordinatorCandidatePayload
import com.afrithecus.brainbox.api.identity.web.GradeConfigSummaryPayload
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.util.UUID

/**
 * The ICT admin's grade-coordinator assignment surface: candidate eligibility, assign,
 * replace (demote the outgoing holder), remove, idempotence, conflicts, tenant isolation
 * and the grade catalogue gate.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class CoordinatorAssignmentWebTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val userRepository: UserRepository,
    @Autowired private val schoolRepository: SchoolRepository,
    @Autowired private val classRepository: TeacherClassRepository,
    @Autowired private val passwordEncoder: PasswordEncoder,
) {

    private lateinit var school: SchoolEntity
    private lateinit var otherSchool: SchoolEntity
    private lateinit var ictAdmin: UserEntity
    private lateinit var otherIctAdmin: UserEntity
    private lateinit var platformAdmin: UserEntity
    private lateinit var classTeacher4a: UserEntity
    private lateinit var classTeacher4b: UserEntity
    private lateinit var coordinator5: UserEntity
    private lateinit var classTeacher9: UserEntity
    private lateinit var plainTeacher: UserEntity
    private lateinit var pendingTeacher: UserEntity
    private lateinit var frozenTeacher: UserEntity
    private lateinit var student: UserEntity
    private lateinit var otherSchoolTeacher: UserEntity

    private fun user(
        role: Role,
        name: String,
        phone: String,
        schoolId: UUID?,
        subRole: SubRole? = null,
        grade: String? = null,
        status: AccountStatus = AccountStatus.VERIFIED,
        active: Boolean = true,
    ): UserEntity {
        val entity = UserEntity()
        entity.phoneNumber = phone
        entity.email = phone + "@coordinator.test"
        entity.passwordHash = passwordEncoder.encode("password123") ?: error("encode")
        entity.name = name
        entity.role = role
        entity.subRole = subRole
        entity.schoolId = schoolId
        entity.gradeLevel = grade
        entity.isActive = active
        entity.isVerified = status == AccountStatus.VERIFIED
        entity.verificationStatus = status
        return userRepository.save(entity)
    }

    private fun clazz(owner: UserEntity, grade: String, name: String) = classRepository.save(
        TeacherClassEntity().apply {
            teacherUserId = owner.id
            schoolId = school.id
            this.name = name
            gradeLevel = grade
            subject = "Mathematics"
            isActive = true
        },
    )

    private fun token(entity: UserEntity): String {
        val response = mockMvc.perform(
            post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"identifier\":\"" + entity.phoneNumber + "\",\"password\":\"password123\"}")
        ).andReturn().response
        check(response.status == 200) { "login failed: " + response.status + " " + response.contentAsString }
        return objectMapper.readValue(response.contentAsString, AuthResponse::class.java).sessionToken!!
    }

    private fun assignResponse(token: String, grade: String, body: String) =
        mockMvc.perform(
            put("/admin/schools/{schoolId}/grades/{gradeLevel}/coordinator", school.id, grade)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(body)
        ).andReturn().response

    private fun assign(token: String, grade: String, teacherId: String?): GradeConfigSummaryPayload {
        val response = assignResponse(token, grade, objectMapper.writeValueAsString(CoordinatorAssignmentPayload(teacherId)))
        check(response.status == 200) { "assign failed: " + response.status + " " + response.contentAsString }
        return objectMapper.readValue(response.contentAsString, GradeConfigSummaryPayload::class.java)
    }

    private fun grades(token: String): List<GradeConfigSummaryPayload> {
        val body = mockMvc.perform(
            get("/admin/schools/" + school.id + "/grades").header("Authorization", "Bearer " + token)
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return objectMapper.readValue(body, Array<GradeConfigSummaryPayload>::class.java).toList()
    }

    private fun grade(token: String, level: String): GradeConfigSummaryPayload =
        grades(token).first { it.gradeLevel.equals(level, ignoreCase = true) }

    private fun candidates(token: String): List<CoordinatorCandidatePayload> {
        val body = mockMvc.perform(
            get("/admin/schools/" + school.id + "/coordinator-candidates").header("Authorization", "Bearer " + token)
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return objectMapper.readValue(body, Array<CoordinatorCandidatePayload>::class.java).toList()
    }

    private fun coordinatorsOfSchool(): List<UserEntity> =
        userRepository.findAllBySchoolIdAndSubRoleAndIsActiveTrue(school.id, SubRole.GRADE_COORDINATOR)

    private fun auditActions(token: String): List<String> {
        val body = mockMvc.perform(
            get("/admin/schools/" + school.id + "/audit-logs").param("limit", "50").header("Authorization", "Bearer " + token)
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return objectMapper.readValue(body, Array<AuditLogEntryPayload>::class.java).map { it.action }
    }

    @BeforeEach
    fun setUp() {
        school = schoolRepository.save(SchoolEntity().apply { name = "Alliance High School"; isActive = true })
        otherSchool = schoolRepository.save(SchoolEntity().apply { name = "Moi Avenue School"; isActive = true })
        ictAdmin = user(Role.TEACHER, "ICT Admin", "0755600001", school.id, SubRole.ICT_ADMIN)
        otherIctAdmin = user(Role.TEACHER, "Other ICT Admin", "0755600002", otherSchool.id, SubRole.ICT_ADMIN)
        platformAdmin = user(Role.ADMIN, "Platform Admin", "0755600003", null)
        classTeacher4a = user(Role.TEACHER, "Class Teacher Four A", "0755600004", school.id, SubRole.CTEACHER, "Grade 4")
        classTeacher4b = user(Role.TEACHER, "Class Teacher Four B", "0755600005", school.id, SubRole.CTEACHER, "Grade 4")
        coordinator5 = user(Role.TEACHER, "Coordinator Five", "0755600006", school.id, SubRole.GRADE_COORDINATOR, "Grade 5")
        classTeacher9 = user(Role.TEACHER, "Class Teacher Nine", "0755600007", school.id, SubRole.CTEACHER, "Grade 9")
        plainTeacher = user(Role.TEACHER, "Plain Teacher", "0755600008", school.id, grade = "Grade 4")
        pendingTeacher = user(Role.TEACHER, "Pending Teacher", "0755600009", school.id, SubRole.CTEACHER, "Grade 4", AccountStatus.PENDING_VERIFICATION)
        frozenTeacher = user(Role.TEACHER, "Frozen Teacher", "0755600010", school.id, SubRole.CTEACHER, "Grade 4", AccountStatus.FROZEN)
        student = user(Role.STUDENT, "Alice Learner", "0755600011", school.id, grade = "Grade 4")
        otherSchoolTeacher = user(Role.TEACHER, "Other School Teacher", "0755600012", otherSchool.id, SubRole.CTEACHER, "Grade 4")
        clazz(classTeacher4a, "Grade 4", "Grade 4 East")
        clazz(classTeacher4b, "Grade 4", "Grade 4 West")
        clazz(coordinator5, "Grade 5", "Grade 5 West")
        clazz(classTeacher9, "Grade 9", "Grade 9 North")
    }

    @Test
    fun `candidates are the active approved class teachers of the school`() {
        val t = token(ictAdmin)
        val rows = candidates(t)
        check(
            rows.map { it.userId }.toSet() == setOf(
                classTeacher4a.id.toString(),
                classTeacher4b.id.toString(),
                coordinator5.id.toString(),
                classTeacher9.id.toString(),
            ),
        ) { "unexpected candidates: " + rows.map { it.name } }
        check(rows.map { it.name } == rows.map { it.name }.sortedBy { it.lowercase() }) { "candidates must be name-sorted" }
        val four = rows.first { it.userId == classTeacher4a.id.toString() }
        check(four.gradeLevel == null && four.classTeacherOf == "Grade 4 East" && four.subRole == "CTEACHER")
        check(four.phoneNumber == "0755600004")
        val five = rows.first { it.userId == coordinator5.id.toString() }
        check(five.gradeLevel == "Grade 5" && five.classTeacherOf == "Grade 5 West" && five.subRole == "GRADE_COORDINATOR")
    }

    @Test
    fun `assign creates the coordinator and grades expose coordinatorId`() {
        val t = token(ictAdmin)
        val result = assign(t, "Grade 4", classTeacher4a.id.toString())
        check(result.gradeLevel == "Grade 4" && result.coordinatorId == classTeacher4a.id.toString()) { "assign payload: " + result }
        check(result.coordinatorName == "Class Teacher Four A")
        val reloaded = userRepository.findById(classTeacher4a.id).orElseThrow()
        check(reloaded.subRole == SubRole.GRADE_COORDINATOR && reloaded.gradeLevel == "Grade 4")
        check(grade(t, "Grade 4").coordinatorId == classTeacher4a.id.toString())
        check(auditActions(t).any { it == "Assigned coordinator for Grade 4: Class Teacher Four A" })
    }

    @Test
    fun `replace demotes the outgoing coordinator and keeps their class`() {
        val t = token(ictAdmin)
        assign(t, "Grade 4", classTeacher4a.id.toString())
        val result = assign(t, "Grade 4", classTeacher4b.id.toString())
        check(result.coordinatorId == classTeacher4b.id.toString() && result.coordinatorName == "Class Teacher Four B")
        val old = userRepository.findById(classTeacher4a.id).orElseThrow()
        check(old.subRole == SubRole.CTEACHER) { "outgoing coordinator must be demoted" }
        check(old.gradeLevel == "Grade 4") { "the demoted teacher keeps the grade row" }
        check(classRepository.findAllByTeacherUserIdAndIsActiveTrueOrderByNameAsc(old.id).any { it.name == "Grade 4 East" })
        check(coordinatorsOfSchool().filter { it.gradeLevel == "Grade 4" }.single().id == classTeacher4b.id)
        check(grade(t, "Grade 4").coordinatorId == classTeacher4b.id.toString())
    }

    @Test
    fun `remove demotes the holder and clears the grade row`() {
        val t = token(ictAdmin)
        assign(t, "Grade 4", classTeacher4a.id.toString())
        val removed = assign(t, "Grade 4", null)
        check(removed.coordinatorId == null && removed.coordinatorName == null)
        val reloaded = userRepository.findById(classTeacher4a.id).orElseThrow()
        check(reloaded.subRole == SubRole.CTEACHER && reloaded.isActive)
        check(classRepository.findAllByTeacherUserIdAndIsActiveTrueOrderByNameAsc(reloaded.id).any { it.name == "Grade 4 East" })
        check(grade(t, "Grade 4").coordinatorId == null)
        // A body with no teacherId is the same removal request.
        assign(t, "Grade 4", classTeacher4a.id.toString())
        val missingBody = assignResponse(t, "Grade 4", "{}")
        check(missingBody.status == 200) { "empty body should remove: " + missingBody.contentAsString }
        check(grade(t, "Grade 4").coordinatorId == null)
        // Removing when nobody holds the grade is a no-op, not an error.
        val empty = assign(t, "Grade 4", null)
        check(empty.coordinatorId == null)
    }

    @Test
    fun `assigning the current holder twice is idempotent`() {
        val t = token(ictAdmin)
        val first = assign(t, "Grade 4", classTeacher4a.id.toString())
        val second = assign(t, "Grade 4", classTeacher4a.id.toString())
        check(first.coordinatorId == second.coordinatorId && second.coordinatorName == "Class Teacher Four A")
        check(coordinatorsOfSchool().count { it.gradeLevel == "Grade 4" } == 1)
        check(auditActions(t).count { it == "Assigned coordinator for Grade 4: Class Teacher Four A" } == 1)
    }

    @Test
    fun `a coordinator cannot be assigned to a second grade`() {
        val t = token(ictAdmin)
        val response = assignResponse(t, "Grade 4", "{\"teacherId\":\"" + coordinator5.id + "\"}")
        check(response.status == 409) { "expected 409, got " + response.status }
        check(response.contentAsString.contains("Grade 5")) { "message must name the grade they coordinate: " + response.contentAsString }
        check(grade(t, "Grade 4").coordinatorId == null)
        val reloaded = userRepository.findById(coordinator5.id).orElseThrow()
        check(reloaded.subRole == SubRole.GRADE_COORDINATOR && reloaded.gradeLevel == "Grade 5")
    }

    @Test
    fun `a coordinator is idempotently re-assigned to their own grade`() {
        val t = token(ictAdmin)
        val result = assign(t, "Grade 5", coordinator5.id.toString())
        check(result.coordinatorId == coordinator5.id.toString())
        check(auditActions(t).none { it.startsWith("Assigned coordinator for Grade 5") })
    }

    @Test
    fun `ineligible accounts are refused with a clear message`() {
        val t = token(ictAdmin)
        val pending = assignResponse(t, "Grade 4", "{\"teacherId\":\"" + pendingTeacher.id + "\"}")
        check(pending.status == 409 && pending.contentAsString.contains("approved")) { pending.contentAsString }
        val frozen = assignResponse(t, "Grade 4", "{\"teacherId\":\"" + frozenTeacher.id + "\"}")
        check(frozen.status == 409) { frozen.contentAsString }
        val plain = assignResponse(t, "Grade 4", "{\"teacherId\":\"" + plainTeacher.id + "\"}")
        check(plain.status == 409 && plain.contentAsString.contains("class teachers")) { plain.contentAsString }
        val otherSchoolTeacherResponse = assignResponse(t, "Grade 4", "{\"teacherId\":\"" + otherSchoolTeacher.id + "\"}")
        check(otherSchoolTeacherResponse.status == 404) { "another school's staff must not be confirmed" }
        val unknown = assignResponse(t, "Grade 4", "{\"teacherId\":\"" + UUID.randomUUID() + "\"}")
        check(unknown.status == 404)
        val studentTarget = assignResponse(t, "Grade 4", "{\"teacherId\":\"" + student.id + "\"}")
        check(studentTarget.status == 404)
        check(grade(t, "Grade 4").coordinatorId == null)
    }

    @Test
    fun `an incomplete teacher account is refused`() {
        val t = token(ictAdmin)
        val inactive = user(Role.TEACHER, "Inactive Teacher", "0755600013", school.id, SubRole.CTEACHER, "Grade 4", active = false)
        val response = assignResponse(t, "Grade 4", "{\"teacherId\":\"" + inactive.id + "\"}")
        check(response.status == 409 && response.contentAsString.contains("not active")) { response.contentAsString }
    }

    @Test
    fun `another schools ICT admin is forbidden`() {
        val t = token(otherIctAdmin)
        val assignment = assignResponse(t, "Grade 4", "{\"teacherId\":\"" + classTeacher4a.id + "\"}")
        check(assignment.status == 403)
        mockMvc.perform(
            get("/admin/schools/" + school.id + "/coordinator-candidates").header("Authorization", "Bearer " + t)
        ).andExpect(status().isForbidden)
        check(grade(ictAdminToken(), "Grade 4").coordinatorId == null)
    }

    @Test
    fun `platform admin may assign in any school`() {
        val t = token(platformAdmin)
        val result = assign(t, "Grade 9", classTeacher9.id.toString())
        check(result.coordinatorId == classTeacher9.id.toString())
        check(candidates(t).isNotEmpty())
    }

    @Test
    fun `the grade must exist in the school catalogue`() {
        val t = token(ictAdmin)
        val response = assignResponse(t, "Grade 7", "{\"teacherId\":\"" + classTeacher4a.id + "\"}")
        check(response.status == 404) { "expected 404, got " + response.status }
    }

    @Test
    fun `the grade path is matched case-insensitively and canonicalised`() {
        val t = token(ictAdmin)
        val result = assign(t, "grade 4", classTeacher4a.id.toString())
        check(result.gradeLevel == "Grade 4") { "canonical grade expected, got " + result.gradeLevel }
        check(grade(t, "Grade 4").coordinatorId == classTeacher4a.id.toString())
    }

    @Test
    fun `an invalid teacher id is a bad request`() {
        val t = token(ictAdmin)
        val response = assignResponse(t, "Grade 4", "{\"teacherId\":\"not-a-uuid\"}")
        check(response.status == 400) { "expected 400, got " + response.status }
    }

    private fun ictAdminToken(): String = token(ictAdmin)

}