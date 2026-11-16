package com.afrithecus.brainbox.api.identity

import com.afrithecus.brainbox.api.auth.web.AuthResponse
import com.afrithecus.brainbox.api.classes.entity.ClassMembershipEntity
import com.afrithecus.brainbox.api.classes.entity.TeacherClassEntity
import com.afrithecus.brainbox.api.classes.repository.ClassMembershipRepository
import com.afrithecus.brainbox.api.classes.repository.StudentClassTransitionRepository
import com.afrithecus.brainbox.api.classes.repository.TeacherClassRepository
import com.afrithecus.brainbox.api.identity.entity.SchoolEntity
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.model.AccountKind
import com.afrithecus.brainbox.api.identity.model.AccountStatus
import com.afrithecus.brainbox.api.identity.model.Role
import com.afrithecus.brainbox.api.identity.model.SubRole
import com.afrithecus.brainbox.api.identity.repository.SchoolRepository
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import com.afrithecus.brainbox.api.identity.web.LearnerDetailPayload
import com.afrithecus.brainbox.api.identity.web.LearnerPagePayload
import com.afrithecus.brainbox.api.identity.web.LearnerTransferResultPayload
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
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Learner management for the ICT admin console (findings "user management"). Proves
 * the tenant boundary (ICT admin own school only, platform ADMIN cross-school),
 * the grade/class/status filters + pagination, the detail payload, the CSV export
 * guards and both transfer kinds (class move reuses the audited transition
 * pipeline; cross-school transfer is platform-admin only).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AdminLearnerWebTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val userRepository: UserRepository,
    @Autowired private val schoolRepository: SchoolRepository,
    @Autowired private val classRepository: TeacherClassRepository,
    @Autowired private val membershipRepository: ClassMembershipRepository,
    @Autowired private val transitionRepository: StudentClassTransitionRepository,
    @Autowired private val passwordEncoder: PasswordEncoder,
) {
    private lateinit var school: SchoolEntity
    private lateinit var otherSchool: SchoolEntity
    private lateinit var admin: UserEntity
    private lateinit var platformAdmin: UserEntity
    private lateinit var teacher: UserEntity
    private lateinit var lion: TeacherClassEntity
    private lateinit var buffalo: TeacherClassEntity
    private lateinit var alice: UserEntity
    private lateinit var ben: UserEntity
    private lateinit var otherLearner: UserEntity

    private fun user(
        role: Role,
        name: String,
        phone: String,
        subRole: SubRole? = null,
        inSchool: SchoolEntity? = school,
        verification: AccountStatus = AccountStatus.VERIFIED,
        active: Boolean = true,
    ): UserEntity = userRepository.save(UserEntity().apply {
        this.name = name
        phoneNumber = phone
        email = phone + "@learners.test"
        passwordHash = passwordEncoder.encode("password123") ?: error("encode")
        this.role = role
        this.subRole = subRole
        schoolId = inSchool?.id
        isActive = active
        isVerified = true
        verificationStatus = verification
    })

    private fun learner(name: String, phone: String, admission: String, grade: String, inSchool: SchoolEntity = school): UserEntity =
        userRepository.save(UserEntity().apply {
            this.name = name
            phoneNumber = phone
            email = null
            passwordHash = passwordEncoder.encode("1234") ?: error("encode")
            role = Role.STUDENT
            schoolId = inSchool.id
            studentAdmissionNumber = admission
            gradeLevel = grade
            guardianName = name + " guardian"
            guardianPhone = "0733" + admission.takeLast(6)
            isActive = true
            isVerified = true
            verificationStatus = AccountStatus.VERIFIED
            credentialKind = "PIN"
        })

    private fun join(clazz: TeacherClassEntity, student: UserEntity) {
        membershipRepository.save(ClassMembershipEntity().apply {
            classId = clazz.id
            this.studentId = student.id
        })
    }

    private fun classRow(name: String, grade: String, owner: UserEntity): TeacherClassEntity =
        classRepository.save(TeacherClassEntity().apply {
            teacherUserId = owner.id
            schoolId = school.id
            this.name = name
            gradeLevel = grade
            subject = "Mathematics"
            isActive = true
        })

    private fun token(entity: UserEntity): String {
        val body = mockMvc.perform(
            post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"identifier\":\"" + entity.phoneNumber + "\",\"password\":\"password123\"}"),
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return objectMapper.readValue(body, AuthResponse::class.java).sessionToken!!
    }

    private fun base(schoolId: UUID = school.id) = "/admin/schools/" + schoolId + "/learners"

    @BeforeEach
    fun setUp() {
        school = schoolRepository.save(SchoolEntity().apply { name = "Milimani Primary"; isActive = true })
        otherSchool = schoolRepository.save(SchoolEntity().apply { name = "Riverside Academy"; isActive = true })
        admin = user(Role.TEACHER, "ICT Admin", "0722300001", SubRole.ICT_ADMIN)
        platformAdmin = user(Role.ADMIN, "Platform Admin", "0722300002", inSchool = null)
        teacher = user(Role.TEACHER, "Class Teacher", "0722300003", SubRole.CTEACHER)
        lion = classRow("Lion", "Grade 5", teacher)
        buffalo = classRow("Buffalo", "Grade 6", teacher)
        alice = learner("Alice Wanjiku", "0722300101", "ADM-001", "Grade 5")
        ben = learner("Ben Otieno", "0722300102", "ADM-002", "Grade 6")
        join(lion, alice)
        join(buffalo, ben)
        otherLearner = learner("Other School Learner", "0722300103", "ADM-101", "Grade 5", inSchool = otherSchool)
    }

    // ------------------------------------------------------------- listing

    @Test
    fun `ICT admin lists their own school learners only`() {
        val body = mockMvc.perform(get(base()).header("Authorization", "Bearer " + token(admin)))
            .andExpect(status().isOk).andReturn().response.contentAsString
        val page = objectMapper.readValue(body, LearnerPagePayload::class.java)
        assertEquals(2, page.total)
        assertEquals(listOf("Alice Wanjiku", "Ben Otieno"), page.items.map { it.name })
        assertTrue(page.items.none { it.studentId == otherLearner.id.toString() })
        assertEquals(2, page.counts.total)
        assertEquals(2, page.counts.active)
        assertEquals(0, page.counts.inactive)
        assertTrue(page.filters.grades.contains("Grade 5"))
        assertTrue(page.filters.classes.any { it.name == "Lion" })
    }

    @Test
    fun `ICT admin cannot read another school's learner list`() {
        mockMvc.perform(get(base(otherSchool.id)).header("Authorization", "Bearer " + token(admin)))
            .andExpect(status().isForbidden)
    }

    @Test
    fun `platform admin can read any school's learners`() {
        val body = mockMvc.perform(get(base(otherSchool.id)).header("Authorization", "Bearer " + token(platformAdmin)))
            .andExpect(status().isOk).andReturn().response.contentAsString
        val page = objectMapper.readValue(body, LearnerPagePayload::class.java)
        assertEquals(1, page.total)
        assertEquals("Other School Learner", page.items.first().name)
    }

    @Test
    fun `filters by grade, class, status, search and paginates`() {
        userRepository.save(UserEntity().apply {
            name = "Inactive Learner"
            phoneNumber = "0722300104"
            passwordHash = passwordEncoder.encode("1234") ?: error("encode")
            role = Role.STUDENT
            schoolId = school.id
            studentAdmissionNumber = "ADM-003"
            gradeLevel = "Grade 5"
            isActive = false
            verificationStatus = AccountStatus.FROZEN
        })
        val auth = token(admin)

        fun page(vararg params: Pair<String, String>): LearnerPagePayload {
            var request = get(base()).header("Authorization", "Bearer " + auth)
            params.forEach { (key, value) -> request = request.param(key, value) }
            val body = mockMvc.perform(request).andExpect(status().isOk).andReturn().response.contentAsString
            return objectMapper.readValue(body, LearnerPagePayload::class.java)
        }

        assertEquals(2, page("gradeLevel" to "Grade 5").total)
        assertEquals(1, page("classId" to lion.id.toString()).total)
        assertEquals(1, page("status" to "INACTIVE").total)
        assertEquals(1, page("status" to "FROZEN").total)
        assertEquals(1, page("q" to "Otieno").total)
        // Counts ignore the status filter, so the header stays a school-wide summary.
        val inactivePage = page("status" to "INACTIVE")
        assertEquals(3, inactivePage.counts.total)
        assertEquals(2, inactivePage.counts.active)
        assertEquals(1, inactivePage.counts.inactive)
        val paged = page("page" to "1", "size" to "1")
        assertEquals(1, paged.page)
        assertEquals(1, paged.size)
        assertEquals(3, paged.totalPages) // alice + ben + the inactive learner added above
        assertEquals("Ben Otieno", paged.items.first().name)
    }

    @Test
    fun `rejects an unknown status with the allowed values`() {
        mockMvc.perform(get(base()).param("status", "NOPE").header("Authorization", "Bearer " + token(admin)))
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `rejects an unknown class id without leaking another tenant's class`() {
        mockMvc.perform(get(base()).param("classId", UUID.randomUUID().toString()).header("Authorization", "Bearer " + token(admin)))
            .andExpect(status().isNotFound)
    }

    // -------------------------------------------------------------- detail

    @Test
    fun `detail returns identity, admission, classes and guardian`() {
        val body = mockMvc.perform(get(base() + "/" + alice.id).header("Authorization", "Bearer " + token(admin)))
            .andExpect(status().isOk).andReturn().response.contentAsString
        val detail = objectMapper.readValue(body, LearnerDetailPayload::class.java)
        assertEquals("Alice Wanjiku", detail.name)
        assertEquals("ADM-001", detail.admissionNumber)
        assertEquals("Grade 5", detail.gradeLevel)
        assertEquals(listOf("Lion"), detail.classNames)
        assertEquals("Alice Wanjiku guardian", detail.guardianName)
        assertEquals("VERIFIED", detail.status)
        assertTrue(detail.joinedAt > 0)
    }

    @Test
    fun `another school's learner detail is a 404, not a cross-tenant read`() {
        mockMvc.perform(get(base() + "/" + otherLearner.id).header("Authorization", "Bearer " + token(admin)))
            .andExpect(status().isNotFound)
    }

    // ------------------------------------------------------------ transfers

    @Test
    fun `class transfer moves the learner and records the transition`() {
        // A fresh class in this school, so the move is real (leaves Lion).
        val physics = classRow("Physics", "Grade 7", teacher)
        val body = mockMvc.perform(
            post(base() + "/" + alice.id + "/transfer")
                .header("Authorization", "Bearer " + token(admin))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"toClassId\":\"" + physics.id + "\",\"reason\":\"Stream correction\"}"),
        ).andExpect(status().isOk).andReturn().response.contentAsString
        val result = objectMapper.readValue(body, LearnerTransferResultPayload::class.java)
        assertEquals("CLASS", result.kind)
        assertEquals(physics.id.toString(), result.toClassId)
        assertEquals(1, transitionRepository.findAllByStudentIdOrderByCreatedAtDesc(alice.id).size)
        val reloaded = userRepository.findById(alice.id).orElseThrow()
        assertEquals("Grade 7", reloaded.gradeLevel)
        assertTrue(membershipRepository.findAllByStudentId(alice.id).none { it.classId == lion.id })
        assertTrue(membershipRepository.findAllByStudentId(alice.id).any { it.classId == physics.id })
    }

    @Test
    fun `roster-only learners cannot be moved into a class`() {
        val rosterOnly = userRepository.save(UserEntity().apply {
            name = "Roster Only Learner"
            phoneNumber = "0722300107"
            passwordHash = passwordEncoder.encode("1234") ?: error("encode")
            role = Role.STUDENT
            schoolId = school.id
            studentAdmissionNumber = "ADM-ROSTER-1"
            gradeLevel = "Grade 5"
            accountKind = AccountKind.ROSTER_ONLY
            isActive = true
            verificationStatus = AccountStatus.VERIFIED
        })
        mockMvc.perform(
            post(base() + "/" + rosterOnly.id + "/transfer")
                .header("Authorization", "Bearer " + token(admin))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"toClassId\":\"" + lion.id + "\",\"reason\":\"Should not join a class\"}"),
        ).andExpect(status().isBadRequest)
        assertTrue(membershipRepository.findAllByStudentId(rosterOnly.id).isEmpty())
    }

    @Test
    fun `ICT admin cannot move a learner into another school's class`() {
        val foreignClass = classRepository.save(TeacherClassEntity().apply {
            teacherUserId = teacher.id
            schoolId = otherSchool.id
            name = "Foreign"
            gradeLevel = "Grade 5"
            subject = "Mathematics"
            isActive = true
        })
        mockMvc.perform(
            post(base() + "/" + alice.id + "/transfer")
                .header("Authorization", "Bearer " + token(admin))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"toClassId\":\"" + foreignClass.id + "\",\"reason\":\"Wrong school\"}"),
        ).andExpect(status().isNotFound)
    }

    @Test
    fun `ICT admin cross-school transfer is refused with an actionable message`() {
        val response = mockMvc.perform(
            post(base() + "/" + alice.id + "/transfer")
                .header("Authorization", "Bearer " + token(admin))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"targetSchoolId\":\"" + otherSchool.id + "\",\"reason\":\"Family moved\"}"),
        ).andExpect(status().isForbidden).andReturn().response.contentAsString
        assertTrue(response.contains("platform administrator"))
        // Nothing changed for the learner.
        assertEquals(school.id, userRepository.findById(alice.id).orElseThrow().schoolId)
    }

    @Test
    fun `platform admin transfers a learner to another school`() {
        val body = mockMvc.perform(
            post(base() + "/" + alice.id + "/transfer")
                .header("Authorization", "Bearer " + token(platformAdmin))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"targetSchoolId\":\"" + otherSchool.id + "\",\"reason\":\"Family moved\"}"),
        ).andExpect(status().isOk).andReturn().response.contentAsString
        val result = objectMapper.readValue(body, LearnerTransferResultPayload::class.java)
        assertEquals("SCHOOL", result.kind)
        assertEquals(otherSchool.id.toString(), result.toSchoolId)
        val reloaded = userRepository.findById(alice.id).orElseThrow()
        assertEquals(otherSchool.id, reloaded.schoolId)
        assertTrue(reloaded.gradeLevel == null)
        assertTrue(membershipRepository.findAllByStudentId(alice.id).isEmpty())
        // The learner now lists under the target school for a platform admin.
        val targetBody = mockMvc.perform(get(base(otherSchool.id)).header("Authorization", "Bearer " + token(platformAdmin)))
            .andExpect(status().isOk).andReturn().response.contentAsString
        assertTrue(objectMapper.readValue(targetBody, LearnerPagePayload::class.java).items.any { it.name == "Alice Wanjiku" })
    }

    @Test
    fun `school transfer keeps the admission number and the learner lists under the target school`() {
        // users.student_admission_number carries a platform-wide unique index, so a transfer
        // must preserve the learner's admission number as-is.
        mockMvc.perform(
            post(base() + "/" + alice.id + "/transfer")
                .header("Authorization", "Bearer " + token(platformAdmin))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"targetSchoolId\":\"" + otherSchool.id + "\",\"reason\":\"Family moved\"}"),
        ).andExpect(status().isOk)
        val reloaded = userRepository.findById(alice.id).orElseThrow()
        assertEquals("ADM-001", reloaded.studentAdmissionNumber)
        assertEquals(otherSchool.id, reloaded.schoolId)
        val targetBody = mockMvc.perform(
            get(base(otherSchool.id)).param("q", "ADM-001").header("Authorization", "Bearer " + token(platformAdmin)),
        ).andExpect(status().isOk).andReturn().response.contentAsString
        val targetPage = objectMapper.readValue(targetBody, LearnerPagePayload::class.java)
        assertEquals(1, targetPage.total)
        assertEquals("Alice Wanjiku", targetPage.items.first().name)
    }
    @Test
    fun `transfer requires exactly one destination and a reason`() {
        val auth = token(admin)
        mockMvc.perform(
            post(base() + "/" + alice.id + "/transfer")
                .header("Authorization", "Bearer " + auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"nothing chosen\"}"),
        ).andExpect(status().isBadRequest)
        mockMvc.perform(
            post(base() + "/" + alice.id + "/transfer")
                .header("Authorization", "Bearer " + auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"toClassId\":\"" + lion.id + "\",\"targetSchoolId\":\"" + otherSchool.id + "\",\"reason\":\"both\"}"),
        ).andExpect(status().isBadRequest)
        mockMvc.perform(
            post(base() + "/" + alice.id + "/transfer")
                .header("Authorization", "Bearer " + auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"toClassId\":\"" + lion.id + "\",\"reason\":\"\"}"),
        ).andExpect(status().isBadRequest)
    }

    @Test
    fun `another school's learner cannot be transferred from this school`() {
        mockMvc.perform(
            post(base() + "/" + otherLearner.id + "/transfer")
                .header("Authorization", "Bearer " + token(admin))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"toClassId\":\"" + lion.id + "\",\"reason\":\"wrong learner\"}"),
        ).andExpect(status().isNotFound)
    }

    // ---------------------------------------------------------------- export

    @Test
    fun `CSV export matches the filters, quotes cells and blocks formula injection`() {
        userRepository.save(UserEntity().apply {
            name = "=SUM(1,1)"
            phoneNumber = "0722300106"
            passwordHash = passwordEncoder.encode("1234") ?: error("encode")
            role = Role.STUDENT
            schoolId = school.id
            studentAdmissionNumber = "ADM-004"
            gradeLevel = "Grade 5"
            guardianName = "Amina, Yusuf"
            guardianPhone = "0733000006"
            isActive = true
            verificationStatus = AccountStatus.VERIFIED
        })
        val response = mockMvc.perform(
            get(base() + "/export").param("gradeLevel", "Grade 5").header("Authorization", "Bearer " + token(admin)),
        ).andExpect(status().isOk).andReturn().response
        assertEquals("text/csv", response.contentType?.split(";")?.first())
        assertTrue(response.getHeader("Content-Disposition")?.contains("attachment; filename=\"learners-") == true)
        val csv = response.getContentAsString(Charsets.UTF_8)
        assertTrue(csv.contains("Name,Admission No,Grade,Classes,Guardian Name"))
        // Same filters as the table: only Grade 5 learners, not the Grade 6 learner.
        assertTrue(csv.contains("Alice Wanjiku"))
        assertTrue(!csv.contains("Ben Otieno"))
        // A spreadsheet formula cannot execute: the cell is prefixed with an apostrophe.
        assertTrue(csv.contains("'=SUM(1,1)"))
        // A comma inside a guardian name is quoted, not split into a new column.
        assertTrue(csv.contains("\"Amina, Yusuf\""))
    }

    @Test
    fun `export is school-scoped like the list`() {
        mockMvc.perform(get(base(otherSchool.id) + "/export").header("Authorization", "Bearer " + token(admin)))
            .andExpect(status().isForbidden)
    }
}
