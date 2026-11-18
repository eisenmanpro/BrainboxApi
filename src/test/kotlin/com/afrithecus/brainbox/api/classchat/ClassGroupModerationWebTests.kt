package com.afrithecus.brainbox.api.classchat

import com.afrithecus.brainbox.api.auth.web.AuthResponse
import com.afrithecus.brainbox.api.classchat.web.AdminArchiveGroupRequest
import com.afrithecus.brainbox.api.classchat.web.AdminFreezeGroupRequest
import com.afrithecus.brainbox.api.classchat.web.ClassGroupPayload
import com.afrithecus.brainbox.api.classchat.web.GroupCreateOptionsPayload
import com.afrithecus.brainbox.api.classes.entity.ClassMembershipEntity
import com.afrithecus.brainbox.api.classes.entity.TeacherClassEntity
import com.afrithecus.brainbox.api.classes.repository.ClassMembershipRepository
import com.afrithecus.brainbox.api.classes.repository.TeacherClassRepository
import com.afrithecus.brainbox.api.identity.entity.SchoolEntity
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.model.AccountStatus
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.util.UUID

/**
 * Group scope/audience/moderation (findings2 GROUPS): class vs grade groups, who may see and
 * join, the create options the dialog needs, and the ICT admin's list/join/freeze/archive
 * moderation with the creator-only delete rule.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ClassGroupModerationWebTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val userRepository: UserRepository,
    @Autowired private val schoolRepository: SchoolRepository,
    @Autowired private val classRepository: TeacherClassRepository,
    @Autowired private val membershipRepository: ClassMembershipRepository,
    @Autowired private val passwordEncoder: PasswordEncoder,
) {

    private lateinit var school: SchoolEntity
    private lateinit var otherSchool: SchoolEntity
    private lateinit var ictAdmin: UserEntity
    private lateinit var otherIctAdmin: UserEntity
    private lateinit var platformAdmin: UserEntity
    private lateinit var ownerTeacher: UserEntity
    private lateinit var otherTeacher: UserEntity
    private lateinit var coordinator: UserEntity
    private lateinit var class5a: TeacherClassEntity
    private lateinit var class5b: TeacherClassEntity
    private lateinit var class6: TeacherClassEntity
    private lateinit var student5a: UserEntity
    private lateinit var student6: UserEntity

    private fun auth(token: String) = "Bearer " + token

    private fun user(
        role: Role,
        name: String,
        phone: String,
        schoolId: UUID?,
        subRole: SubRole? = null,
        grade: String? = null,
    ): UserEntity = userRepository.save(UserEntity().apply {
        this.phoneNumber = phone
        this.email = phone + "@groups.test"
        passwordHash = passwordEncoder.encode("password123") ?: error("encode")
        this.name = name
        this.role = role
        this.subRole = subRole
        this.schoolId = schoolId
        this.gradeLevel = grade
        isActive = true
        isVerified = true
        verificationStatus = AccountStatus.VERIFIED
    })

    private fun token(entity: UserEntity): String {
        val body = mockMvc.perform(
            post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{" + "\"identifier\":\"" + entity.phoneNumber + "\",\"password\":\"password123\"}")
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return objectMapper.readValue(body, AuthResponse::class.java).sessionToken!!
    }

    private fun clazz(owner: UserEntity, grade: String, name: String): TeacherClassEntity =
        classRepository.save(TeacherClassEntity().apply {
            teacherUserId = owner.id
            schoolId = this@ClassGroupModerationWebTests.school.id
            this.name = name
            gradeLevel = grade
            subject = "Science"
            isActive = true
        })

    private fun membership(student: UserEntity, clazz: TeacherClassEntity) {
        membershipRepository.save(ClassMembershipEntity().apply {
            classId = clazz.id
            studentId = student.id
        })
    }

    private fun createGroup(
        token: String,
        teacher: UserEntity,
        name: String,
        scope: String? = null,
        classId: UUID? = null,
        gradeLevel: String? = null,
        audience: String? = null,
        memberIds: List<UUID> = emptyList(),
    ): ClassGroupPayload {
        var builder: MockHttpServletRequestBuilder = post("/teacher/class-groups")
            .header("Authorization", auth(token))
            .param("teacherId", teacher.id.toString())
            .param("name", name)
        if (scope != null) builder = builder.param("scope", scope)
        if (classId != null) builder = builder.param("classId", classId.toString())
        if (gradeLevel != null) builder = builder.param("gradeLevel", gradeLevel)
        if (audience != null) builder = builder.param("audience", audience)
        val response = mockMvc.perform(
            builder.contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(memberIds.map { it.toString() }))
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return objectMapper.readValue(response, ClassGroupPayload::class.java)
    }

    private fun groups(token: String, teacher: UserEntity): List<ClassGroupPayload> {
        val body = mockMvc.perform(
            get("/teacher/class-groups").param("teacherId", teacher.id.toString()).header("Authorization", auth(token))
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return objectMapper.readValue(body, Array<ClassGroupPayload>::class.java).toList()
    }

    private fun createOptions(token: String): GroupCreateOptionsPayload {
        val body = mockMvc.perform(
            get("/teacher/class-groups/create-options").header("Authorization", auth(token))
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return objectMapper.readValue(body, GroupCreateOptionsPayload::class.java)
    }

    private fun adminGroups(token: String, schoolId: UUID = school.id): List<ClassGroupPayload> {
        val body = mockMvc.perform(
            get("/admin/schools/" + schoolId + "/class-groups").header("Authorization", auth(token))
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return objectMapper.readValue(body, Array<ClassGroupPayload>::class.java).toList()
    }

    private fun freezeRaw(token: String, groupId: String, isFrozen: Boolean, reason: String?) =
        mockMvc.perform(
            post("/admin/schools/" + school.id + "/class-groups/" + groupId + "/freeze")
                .header("Authorization", auth(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(AdminFreezeGroupRequest(isFrozen, reason)))
        ).andReturn()

    private fun archiveRaw(token: String, groupId: String, isArchived: Boolean, termLabel: String?) =
        mockMvc.perform(
            post("/admin/schools/" + school.id + "/class-groups/" + groupId + "/archive")
                .header("Authorization", auth(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(AdminArchiveGroupRequest(isArchived, termLabel, null)))
        ).andReturn()

    private fun send(token: String, groupId: String, text: String) =
        mockMvc.perform(
            post("/teacher/class-groups/" + groupId + "/messages").param("text", text).header("Authorization", auth(token))
        )

    @BeforeEach
    fun setUp() {
        school = schoolRepository.save(SchoolEntity().apply { name = "Milimani Primary"; isActive = true })
        otherSchool = schoolRepository.save(SchoolEntity().apply { name = "Moi Avenue Primary"; isActive = true })
        ictAdmin = user(Role.TEACHER, "ICT Admin", "0755700001", school.id, SubRole.ICT_ADMIN)
        otherIctAdmin = user(Role.TEACHER, "Other ICT Admin", "0755700002", otherSchool.id, SubRole.ICT_ADMIN)
        platformAdmin = user(Role.ADMIN, "Platform Admin", "0755700003", null)
        ownerTeacher = user(Role.TEACHER, "Owner Teacher", "0755700004", school.id)
        otherTeacher = user(Role.TEACHER, "Other Teacher", "0755700005", school.id)
        coordinator = user(Role.TEACHER, "Grade Coordinator", "0755700006", school.id, SubRole.GRADE_COORDINATOR, "Grade 5")
        class5a = clazz(ownerTeacher, "Grade 5", "Grade 5 East")
        class5b = clazz(coordinator, "Grade 5", "Grade 5 West")
        class6 = clazz(ownerTeacher, "Grade 6", "Grade 6 North")
        student5a = user(Role.STUDENT, "Learner Five A", "0755700007", school.id, grade = "Grade 5")
        student6 = user(Role.STUDENT, "Learner Six", "0755700008", school.id, grade = "Grade 6")
        membership(student5a, class5a)
        membership(student6, class6)
    }

    @Test
    fun `create options offer owned classes and the coordinator's grade classes`() {
        val ownerOptions = createOptions(token(ownerTeacher))
        check(ownerOptions.classes.map { it.name }.toSet() == setOf("Grade 5 East", "Grade 6 North"))
        check(ownerOptions.grades.contains("Grade 5") && ownerOptions.grades.contains("Grade 6"))
        check(ownerOptions.scopes == listOf("CLASS", "GRADE"))
        check(ownerOptions.audiences == listOf("STUDENTS", "TEACHERS", "CLASS_TEACHERS", "ALL"))

        // A coordinator can also target the grade's classes they do not own.
        val coordinatorOptions = createOptions(token(coordinator))
        check(coordinatorOptions.classes.map { it.id }.toSet() == setOf(class5a.id.toString(), class5b.id.toString()))
    }

    @Test
    fun `create options are forbidden to an ICT admin`() {
        mockMvc.perform(
            get("/teacher/class-groups/create-options").header("Authorization", auth(token(ictAdmin)))
        ).andExpect(status().isForbidden)
    }

    @Test
    fun `grade group creation validates catalogue grade and audience`() {
        mockMvc.perform(
            post("/teacher/class-groups")
                .header("Authorization", auth(token(ownerTeacher)))
                .param("teacherId", ownerTeacher.id.toString())
                .param("name", "Grade 9 Science")
                .param("scope", "GRADE")
                .param("gradeLevel", "Grade 9")
                .param("audience", "TEACHERS")
                .contentType(MediaType.APPLICATION_JSON).content("[]")
        ).andExpect(status().isNotFound)

        mockMvc.perform(
            post("/teacher/class-groups")
                .header("Authorization", auth(token(ownerTeacher)))
                .param("teacherId", ownerTeacher.id.toString())
                .param("name", "Grade 5 Science")
                .param("scope", "GRADE")
                .param("gradeLevel", "Grade 5")
                .param("audience", "EVERYONE")
                .contentType(MediaType.APPLICATION_JSON).content("[]")
        ).andExpect(status().isBadRequest)

        val group = createGroup(
            token(ownerTeacher), ownerTeacher, "Grade 5 Science", scope = "GRADE",
            gradeLevel = "Grade 5", audience = "TEACHERS",
        )
        check(group.scope == "GRADE" && group.gradeLevel == "Grade 5" && group.audience == "TEACHERS")
        check(group.classId.isEmpty()) { "a grade group has no class id" }
        check(group.teacherId == ownerTeacher.id.toString())
        check(group.isCreator && group.canDelete && !group.canModerate)
    }

    @Test
    fun `grade group is visible and joinable by teachers in the audience`() {
        val group = createGroup(
            token(ownerTeacher), ownerTeacher, "Grade 5 Science", scope = "GRADE",
            gradeLevel = "Grade 5", audience = "TEACHERS",
        )
        val otherToken = token(otherTeacher)
        val visible = groups(otherToken, otherTeacher).single { it.id == group.id }
        check(visible.canJoin && !visible.isMember)
        mockMvc.perform(
            get("/teacher/class-groups/" + group.id + "/messages").header("Authorization", auth(otherToken))
        ).andExpect(status().isForbidden)

        mockMvc.perform(
            post("/teacher/class-groups/" + group.id + "/join").header("Authorization", auth(otherToken))
        ).andExpect(status().isOk)
        val joined = groups(otherToken, otherTeacher).single { it.id == group.id }
        check(joined.isMember && !joined.canJoin)
        mockMvc.perform(
            get("/teacher/class-groups/" + group.id + "/messages").header("Authorization", auth(otherToken))
        ).andExpect(status().isOk)
        send(otherToken, group.id, "Count me in").andExpect(status().isOk)
    }

    @Test
    fun `class group with the students audience stays hidden from other teachers`() {
        val group = createGroup(token(ownerTeacher), ownerTeacher, "Grade 5 East Club", classId = class5a.id)
        check(group.scope == "CLASS" && group.audience == "STUDENTS")
        val otherToken = token(otherTeacher)
        check(groups(otherToken, otherTeacher).none { it.id == group.id })
        mockMvc.perform(
            post("/teacher/class-groups/" + group.id + "/join").header("Authorization", auth(otherToken))
        ).andExpect(status().isForbidden)
        mockMvc.perform(
            get("/teacher/class-groups/" + group.id + "/messages").header("Authorization", auth(otherToken))
        ).andExpect(status().isForbidden)
        check(groups(token(ownerTeacher), ownerTeacher).any { it.id == group.id })
    }

    @Test
    fun `group members must belong to the class or grade`() {
        mockMvc.perform(
            post("/teacher/class-groups")
                .header("Authorization", auth(token(ownerTeacher)))
                .param("teacherId", ownerTeacher.id.toString())
                .param("name", "Grade 5 Science")
                .param("scope", "GRADE")
                .param("gradeLevel", "Grade 5")
                .param("audience", "STUDENTS")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(listOf(student6.id.toString())))
        ).andExpect(status().isBadRequest)

        val gradeGroup = createGroup(
            token(ownerTeacher), ownerTeacher, "Grade 5 Science", scope = "GRADE",
            gradeLevel = "Grade 5", audience = "STUDENTS", memberIds = listOf(student5a.id),
        )
        check(gradeGroup.memberCount == 1)

        mockMvc.perform(
            post("/teacher/class-groups")
                .header("Authorization", auth(token(ownerTeacher)))
                .param("teacherId", ownerTeacher.id.toString())
                .param("name", "Grade 5 East Club")
                .param("classId", class5a.id.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(listOf(student6.id.toString())))
        ).andExpect(status().isBadRequest)
    }

    @Test
    fun `admin lists every group and can join and contribute for moderation`() {
        val classGroup = createGroup(token(ownerTeacher), ownerTeacher, "Grade 5 East Club", classId = class5a.id)
        val gradeGroup = createGroup(
            token(ownerTeacher), ownerTeacher, "Grade 5 Science", scope = "GRADE",
            gradeLevel = "Grade 5", audience = "TEACHERS",
        )
        val adminToken = token(ictAdmin)
        val listed = adminGroups(adminToken).map { it.id }.toSet()
        check(listed.contains(classGroup.id) && listed.contains(gradeGroup.id))
        val row = adminGroups(adminToken).first { it.id == classGroup.id }
        check(row.canModerate && !row.isCreator)

        mockMvc.perform(
            post("/teacher/class-groups/" + classGroup.id + "/join").header("Authorization", auth(adminToken))
        ).andExpect(status().isOk)
        send(adminToken, classGroup.id, "Moderator note").andExpect(status().isOk)
        check(groups(adminToken, ictAdmin).first { it.id == classGroup.id }.isMember)
    }

    @Test
    fun `admin freeze requires a reason and blocks writes until it is lifted`() {
        val group = createGroup(token(ownerTeacher), ownerTeacher, "Grade 5 East Club", classId = class5a.id)
        val adminToken = token(ictAdmin)
        val missingReason = freezeRaw(adminToken, group.id, true, "  ")
        check(missingReason.response.status == 400)

        val frozen = freezeRaw(adminToken, group.id, true, "Exam week")
        check(frozen.response.status == 200)
        val payload = objectMapper.readValue(frozen.response.contentAsString, ClassGroupPayload::class.java)
        check(payload.isFrozen && payload.frozenReason == "Exam week")

        val blockedResponse = send(token(ownerTeacher), group.id, "Still open?").andReturn().response
        check(blockedResponse.status == 403)
        check(blockedResponse.contentAsString.contains("frozen"))
        check(blockedResponse.contentAsString.contains("Exam week"))

        val unfrozen = freezeRaw(adminToken, group.id, false, null)
        check(unfrozen.response.status == 200)
        send(token(ownerTeacher), group.id, "Open again").andExpect(status().isOk)
    }

    @Test
    fun `admin can archive with a term label and cannot delete and the creator can`() {
        val group = createGroup(token(ownerTeacher), ownerTeacher, "Grade 5 East Club", classId = class5a.id)
        val adminToken = token(ictAdmin)
        val missingTerm = archiveRaw(adminToken, group.id, true, null)
        check(missingTerm.response.status == 400)

        val archived = archiveRaw(adminToken, group.id, true, "Term 2 2026")
        check(archived.response.status == 200)
        val payload = objectMapper.readValue(archived.response.contentAsString, ClassGroupPayload::class.java)
        check(payload.isArchived && payload.termLabel == "Term 2 2026")
        val blockedResponse = send(token(ownerTeacher), group.id, "Read only").andReturn().response
        check(blockedResponse.status == 403)
        check(blockedResponse.contentAsString.contains("archived"))

        mockMvc.perform(
            delete("/teacher/class-groups/" + group.id).header("Authorization", auth(adminToken))
        ).andExpect(status().isForbidden)
        mockMvc.perform(
            delete("/teacher/class-groups/" + group.id).header("Authorization", auth(token(ownerTeacher)))
        ).andExpect(status().isNoContent)
    }

    @Test
    fun `another school's admin is forbidden and the platform admin may moderate anywhere`() {
        val group = createGroup(token(ownerTeacher), ownerTeacher, "Grade 5 East Club", classId = class5a.id)
        val otherToken = token(otherIctAdmin)
        mockMvc.perform(
            get("/admin/schools/" + school.id + "/class-groups").header("Authorization", auth(otherToken))
        ).andExpect(status().isForbidden)
        check(freezeRaw(otherToken, group.id, true, "Not mine").response.status == 403)

        val platformToken = token(platformAdmin)
        check(adminGroups(platformToken).any { it.id == group.id })
        check(freezeRaw(platformToken, group.id, true, "Platform review").response.status == 200)
    }
}
