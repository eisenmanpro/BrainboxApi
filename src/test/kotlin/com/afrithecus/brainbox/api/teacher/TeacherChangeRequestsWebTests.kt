package com.afrithecus.brainbox.api.teacher

import com.afrithecus.brainbox.api.auth.web.AuthResponse
import com.afrithecus.brainbox.api.classes.entity.TeacherClassEntity
import com.afrithecus.brainbox.api.classes.repository.TeacherClassRepository
import com.afrithecus.brainbox.api.identity.entity.SchoolEntity
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.model.Role
import com.afrithecus.brainbox.api.identity.model.SubRole
import com.afrithecus.brainbox.api.identity.repository.SchoolRepository
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import com.afrithecus.brainbox.api.identity.web.SchoolClassPayload
import com.afrithecus.brainbox.api.identity.web.SchoolConfigPayload
import com.afrithecus.brainbox.api.notification.repository.NotificationRepository
import com.afrithecus.brainbox.api.teacher.web.CreateTeacherChangeRequest
import com.afrithecus.brainbox.api.teacher.web.TeacherChangeDecisionRequest
import com.afrithecus.brainbox.api.teacher.web.TeacherChangeRequestPayload
import com.afrithecus.brainbox.api.teacher.web.TeacherProfilePayload
import com.afrithecus.brainbox.api.teacher.web.TeacherSchoolConfigPayload
import com.afrithecus.brainbox.api.teacher.web.TeacherSettingsPayload
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
 * N3: server-driven school configuration, the teacher change-request gate and
 * the enforcement that a governed field cannot be written directly.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class TeacherChangeRequestsWebTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val userRepository: UserRepository,
    @Autowired private val schoolRepository: SchoolRepository,
    @Autowired private val classRepository: TeacherClassRepository,
    @Autowired private val notificationRepository: NotificationRepository,
    @Autowired private val passwordEncoder: PasswordEncoder,
) {

    private lateinit var school: SchoolEntity
    private lateinit var otherSchool: SchoolEntity
    private lateinit var admin: UserEntity
    private lateinit var otherAdmin: UserEntity
    private lateinit var teacher: UserEntity

    private fun user(
        role: Role,
        name: String,
        phone: String,
        schoolId: UUID?,
        subRole: SubRole? = null,
    ): UserEntity = userRepository.save(UserEntity().apply {
        phoneNumber = phone
        email = phone + "@change.test"
        passwordHash = passwordEncoder.encode("password123") ?: error("encode")
        this.name = name
        this.role = role
        this.subRole = subRole
        this.schoolId = schoolId
        isActive = true
        isVerified = true
    })

    private fun token(entity: UserEntity): String {
        val body = mockMvc.perform(
            post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(mapOf("identifier" to entity.phoneNumber, "password" to "password123"))),
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return objectMapper.readValue(body, AuthResponse::class.java).sessionToken!!
    }

    private fun auth(token: String) = "Bearer " + token

    private fun putConfig(adminToken: String, schoolId: UUID, classes: List<SchoolClassPayload>?) {
        val body = SchoolConfigPayload(
            schoolId = schoolId.toString(),
            schoolName = if (schoolId == school.id) school.name else otherSchool.name,
            classes = classes,
        )
        mockMvc.perform(
            put("/admin/schools/" + schoolId + "/config").header("Authorization", auth(adminToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)),
        ).andExpect(status().isOk)
    }

    private fun createRequest(token: String, request: CreateTeacherChangeRequest): TeacherChangeRequestPayload {
        val body = mockMvc.perform(
            post("/teacher/change-requests").header("Authorization", auth(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)),
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return objectMapper.readValue(body, TeacherChangeRequestPayload::class.java)
    }

    private fun queue(adminToken: String, schoolId: UUID): List<TeacherChangeRequestPayload> {
        val body = mockMvc.perform(
            get("/admin/schools/" + schoolId + "/teacher-change-requests")
                .header("Authorization", auth(adminToken)),
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return objectMapper.readValue(body, Array<TeacherChangeRequestPayload>::class.java).toList()
    }

    private fun decide(
        adminToken: String,
        schoolId: UUID,
        requestId: String,
        action: String,
        note: String? = null,
    ): TeacherChangeRequestPayload {
        val body = mockMvc.perform(
            post("/admin/schools/" + schoolId + "/teacher-change-requests/" + requestId + "/" + action)
                .header("Authorization", auth(adminToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(TeacherChangeDecisionRequest(note))),
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return objectMapper.readValue(body, TeacherChangeRequestPayload::class.java)
    }

    private fun profile(token: String): TeacherProfilePayload {
        val body = mockMvc.perform(get("/teacher/profile").header("Authorization", auth(token)))
            .andExpect(status().isOk).andReturn().response.contentAsString
        return objectMapper.readValue(body, TeacherProfilePayload::class.java)
    }

    private fun settings(token: String): TeacherSettingsPayload {
        val body = mockMvc.perform(get("/teacher/settings").header("Authorization", auth(token)))
            .andExpect(status().isOk).andReturn().response.contentAsString
        return objectMapper.readValue(body, TeacherSettingsPayload::class.java)
    }

    @BeforeEach
    fun setUp() {
        school = schoolRepository.save(SchoolEntity().apply { name = "Alliance High School"; isActive = true })
        otherSchool = schoolRepository.save(SchoolEntity().apply { name = "Moi Avenue School"; isActive = true })
        admin = user(Role.TEACHER, "ICT Admin", "0755600001", school.id, SubRole.ICT_ADMIN)
        otherAdmin = user(Role.TEACHER, "Other ICT Admin", "0755600002", otherSchool.id, SubRole.ICT_ADMIN)
        teacher = user(Role.TEACHER, "Class Teacher", "0755600003", school.id)
    }

    @Test
    fun `a branding-only config save must not wipe the class catalogue`() {
        val adminToken = token(admin)
        putConfig(adminToken, school.id, listOf(SchoolClassPayload("Grade 4 Alpha", "Grade 4")))
        // An older client (or a branding-only editor) PUTs the config without classes;
        // the stored catalogue must survive because the field is a partial update.
        putConfig(adminToken, school.id, null)
        val config = objectMapper.readValue(
            mockMvc.perform(get("/teacher/school-config").header("Authorization", auth(token(teacher))))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            TeacherSchoolConfigPayload::class.java,
        )
        check(config.classes.map { it.name } == listOf("Grade 4 Alpha"))
    }

    @Test
    fun `school config drives the teacher dropdowns`() {
        val adminToken = token(admin)
        putConfig(
            adminToken,
            school.id,
            listOf(
                SchoolClassPayload("Grade 4 Alpha", "Grade 4", "Alpha"),
                SchoolClassPayload("Grade 5 Beta", "Grade 5"),
            ),
        )
        val teacherToken = token(teacher)
        val config = objectMapper.readValue(
            mockMvc.perform(get("/teacher/school-config").header("Authorization", auth(teacherToken)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            TeacherSchoolConfigPayload::class.java,
        )
        check(config.schoolId == school.id.toString())
        check(config.classes.map { it.name } == listOf("Grade 4 Alpha", "Grade 5 Beta"))
        check(config.classes.first().stream == "Alpha")
        check(config.subjects.isNotEmpty()) { "the BrainBox subject catalogue must reach the client" }
        check(config.subjects.any { it.name == "Mathematics" })

        // A learner has no teacher settings to load.
        val student = user(Role.STUDENT, "Learner", "0755600004", school.id)
        mockMvc.perform(get("/teacher/school-config").header("Authorization", auth(token(student))))
            .andExpect(status().isForbidden)
    }

    @Test
    fun `class assignment request is approved by the school admin and applied`() {
        val adminToken = token(admin)
        putConfig(
            adminToken,
            school.id,
            listOf(
                SchoolClassPayload("Grade 4 Alpha", "Grade 4"),
                SchoolClassPayload("Grade 5 Beta", "Grade 5"),
            ),
        )
        val teacherToken = token(teacher)

        val created = createRequest(
            teacherToken,
            CreateTeacherChangeRequest(
                kind = "CLASS_ASSIGNMENT",
                className = "Grade 4 Alpha",
                gradeLevels = listOf("Grade 4"),
            ),
        )
        check(created.status == "PENDING")
        check(created.origin == "WITHIN_SCHOOL")
        check(created.canDecide.not())

        val queued = queue(adminToken, school.id).first()
        check(queued.id == created.id)
        check(queued.teacherName == "Class Teacher")
        check(queued.proposedClassName == "Grade 4 Alpha")
        check(queued.proposedGradeLevels == listOf("Grade 4"))
        check(queued.canDecide) { "the school admin must be able to decide a within-school request" }

        val approved = decide(adminToken, school.id, created.id, "approve")
        check(approved.status == "APPROVED")

        val updated = profile(teacherToken)
        check(updated.className == "Grade 4 Alpha")
        check(updated.gradesTaught == listOf("Grade 4"))
        check(notificationRepository.countByUserIdAndIsReadFalseAndIsArchivedFalse(teacher.id) >= 1)

        // An offline replay of the same approval is idempotent, not a second apply.
        val replayed = decide(adminToken, school.id, created.id, "approve")
        check(replayed.status == "APPROVED")
    }

    @Test
    fun `governed profile writes are refused but a subject request is applied on approval`() {
        val teacherToken = token(teacher)

        // A first-time value is allowed: there is nothing for an admin to approve yet.
        val initial = createRequest(
            teacherToken,
            CreateTeacherChangeRequest(kind = "SUBJECTS", subjects = listOf("mathematics", "English")),
        )
        check(initial.proposedSubjects == listOf("Mathematics", "English")) { "the catalogue must canonicalise names" }

        val adminToken = token(admin)
        decide(adminToken, school.id, initial.id, "approve")
        val updated = settings(teacherToken)
        check(updated.subjectsTaught == listOf("Mathematics", "English"))

        // Changing the approved value through the direct endpoint is refused so the
        // approval gate cannot be bypassed.
        val direct = profile(teacherToken).copy(subjects = listOf("Mathematics"))
        mockMvc.perform(
            put("/teacher/profile").header("Authorization", auth(teacherToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(direct)),
        ).andExpect(status().isConflict)

        // Echoing the current governed values back through the direct endpoint is safe.
        mockMvc.perform(
            put("/teacher/profile").header("Authorization", auth(teacherToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(profile(teacherToken))),
        ).andExpect(status().isOk)

        // The settings document is not an editing surface for subjects: a client saving the
        // whole document for notification preferences must not change or fail the governed field.
        mockMvc.perform(
            put("/teacher/settings").header("Authorization", auth(teacherToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        settings(teacherToken).copy(subjectsTaught = listOf("Physics")),
                    ),
                ),
        ).andExpect(status().isOk)
        check(settings(teacherToken).subjectsTaught == listOf("Mathematics", "English")) {
            "the settings endpoint must never write governed subjects"
        }
    }

    @Test
    fun `a teacher cannot bypass the gate through the admin teacher route`() {
        val teacherToken = token(teacher)
        mockMvc.perform(
            put("/auth/teachers/" + teacher.id).header("Authorization", auth(teacherToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(mapOf("name" to "Renamed Teacher"))),
        ).andExpect(status().isOk)
        mockMvc.perform(
            put("/auth/teachers/" + teacher.id).header("Authorization", auth(teacherToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(mapOf("subjects" to listOf("Physics")))),
        ).andExpect(status().isConflict)
    }

    @Test
    fun `a school admin can approve its own users and only its own`() {
        val student = user(Role.STUDENT, "Learner", "0755600010", school.id)
        student.verificationStatus = com.afrithecus.brainbox.api.identity.model.AccountStatus.PENDING_VERIFICATION
        student.isVerified = false
        userRepository.save(student)

        mockMvc.perform(
            post("/admin/users/" + student.id + "/approve").header("Authorization", auth(token(otherAdmin))),
        ).andExpect(status().isForbidden)
        mockMvc.perform(
            post("/admin/users/" + student.id + "/approve").header("Authorization", auth(token(admin))),
        ).andExpect(status().isNoContent)
    }

    @Test
    fun `onboarding grades come from the school class catalogue`() {
        val adminToken = token(admin)
        putConfig(
            adminToken,
            school.id,
            listOf(
                SchoolClassPayload("Grade 4 Alpha", "Grade 4"),
                SchoolClassPayload("Grade 5 Beta", "Grade 5"),
            ),
        )
        classRepository.save(TeacherClassEntity().apply {
            teacherUserId = admin.id
            schoolId = school.id
            name = "Grade 4 Alpha"
            gradeLevel = "Grade 4"
            subject = "Mathematics"
            isActive = true
        })
        classRepository.save(TeacherClassEntity().apply {
            teacherUserId = admin.id
            schoolId = school.id
            name = "Grade 5 Beta"
            gradeLevel = "Grade 5"
            subject = "English"
            isActive = true
        })
        val body = mockMvc.perform(
            get("/school/grade-configs/onboarding?schoolId=" + school.id)
                .header("Authorization", auth(token(teacher))),
        ).andExpect(status().isOk).andReturn().response.contentAsString
        check(body.contains("Grade 4")) { "the grade picker must see the school catalogue: " + body }
        check(body.contains("Grade 5"))
    }

    @Test
    fun `school transfer is decided by the destination school admin`() {
        val teacherToken = token(teacher)
        val created = createRequest(
            teacherToken,
            CreateTeacherChangeRequest(kind = "SCHOOL", schoolId = otherSchool.id.toString()),
        )
        check(created.origin == "FROM_OTHER_SCHOOL")
        check(created.targetSchoolName == "Moi Avenue School")
        check(created.currentSchoolName == "Alliance High School")

        val homeQueue = queue(token(admin), school.id).first()
        check(homeQueue.id == created.id)
        check(homeQueue.canDecide.not()) { "the school being left cannot decide a transfer" }

        val targetQueue = queue(token(otherAdmin), otherSchool.id).first()
        check(targetQueue.canDecide)

        mockMvc.perform(
            post("/admin/schools/" + school.id + "/teacher-change-requests/" + created.id + "/approve")
                .header("Authorization", auth(token(admin)))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"),
        ).andExpect(status().isNotFound)

        decide(token(otherAdmin), otherSchool.id, created.id, "approve")
        val updated = profile(teacherToken)
        check(updated.schoolId == otherSchool.id.toString())
        check(updated.schoolName == "Moi Avenue School")
    }

    @Test
    fun `one pending request at a time and rejection keeps the profile`() {
        val adminToken = token(admin)
        putConfig(adminToken, school.id, listOf(SchoolClassPayload("Grade 4 Alpha", "Grade 4")))
        val teacherToken = token(teacher)

        mockMvc.perform(
            post("/teacher/change-requests").header("Authorization", auth(teacherToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        CreateTeacherChangeRequest(
                            kind = "CLASS_ASSIGNMENT",
                            className = "Grade 9 Ghost",
                            gradeLevels = listOf("Grade 9"),
                        ),
                    ),
                ),
        ).andExpect(status().isBadRequest)

        val created = createRequest(
            teacherToken,
            CreateTeacherChangeRequest(
                kind = "CLASS_ASSIGNMENT",
                className = "Grade 4 Alpha",
                gradeLevels = listOf("Grade 4"),
            ),
        )
        mockMvc.perform(
            post("/teacher/change-requests").header("Authorization", auth(teacherToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        CreateTeacherChangeRequest(kind = "SUBJECTS", subjects = listOf("English")),
                    ),
                ),
        ).andExpect(status().isConflict)

        val rejected = decide(adminToken, school.id, created.id, "reject", "Not your class yet")
        check(rejected.status == "REJECTED")
        check(rejected.reviewNote == "Not your class yet")
        check(profile(teacherToken).className == null) { "a rejected request must not change the profile" }

        // A rejected request frees the teacher to submit a follow-up, and the
        // subject catalogue rejects a value BrainBox does not offer.
        mockMvc.perform(
            post("/teacher/change-requests").header("Authorization", auth(teacherToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        CreateTeacherChangeRequest(kind = "SUBJECTS", subjects = listOf("Astrology")),
                    ),
                ),
        ).andExpect(status().isBadRequest)

        // Learners cannot reach the admin queue.
        val student = user(Role.STUDENT, "Learner", "0755600005", school.id)
        mockMvc.perform(
            get("/admin/schools/" + school.id + "/teacher-change-requests")
                .header("Authorization", auth(token(student))),
        ).andExpect(status().isForbidden)
    }
}
