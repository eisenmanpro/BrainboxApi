package com.afrithecus.brainbox.api.identity

import com.afrithecus.brainbox.api.auth.web.AuthResponse
import com.afrithecus.brainbox.api.classes.entity.ClassMembershipEntity
import com.afrithecus.brainbox.api.classes.entity.TeacherClassEntity
import com.afrithecus.brainbox.api.classes.repository.ClassMembershipRepository
import com.afrithecus.brainbox.api.classes.repository.TeacherClassRepository
import com.afrithecus.brainbox.api.classes.web.AdminClassPayload
import com.afrithecus.brainbox.api.classes.web.AssignClassTeacherRequest
import com.afrithecus.brainbox.api.classes.web.CreateAdminClassRequest
import com.afrithecus.brainbox.api.classes.web.TeacherClassPayload
import com.afrithecus.brainbox.api.classes.web.UpdateAdminClassRequest
import com.afrithecus.brainbox.api.identity.entity.SchoolEntity
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.model.AccountKind
import com.afrithecus.brainbox.api.identity.model.Role
import com.afrithecus.brainbox.api.identity.model.SubRole
import com.afrithecus.brainbox.api.identity.repository.SchoolRepository
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import com.afrithecus.brainbox.api.identity.web.AdminStudentPayload
import com.afrithecus.brainbox.api.identity.web.SchoolConfigPayload
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.util.UUID

/**
 * Admin class management + the learner audience read (findings "ADMIN SCHOOL CONFIG 3" and
 * "ADMIN ANNOUNCEMENTS 1"): teacher_classes is authoritative and the signup catalogue is
 * re-synced, so the redesigned School Config can offer real grade/class pickers.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AdminClassWebTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val userRepository: UserRepository,
    @Autowired private val schoolRepository: SchoolRepository,
    @Autowired private val classRepository: TeacherClassRepository,
    @Autowired private val membershipRepository: ClassMembershipRepository,
    @Autowired private val passwordEncoder: PasswordEncoder,
) {
    private lateinit var school: SchoolEntity
    private lateinit var admin: UserEntity
    private lateinit var teacherA: UserEntity
    private lateinit var teacherB: UserEntity

    private fun user(role: Role, name: String, phone: String, subRole: SubRole? = null, grade: String? = null): UserEntity =
        userRepository.save(UserEntity().apply {
            this.name = name
            phoneNumber = phone
            email = phone + "@adminclass.test"
            passwordHash = passwordEncoder.encode("password123") ?: error("encode")
            this.role = role
            this.subRole = subRole
            schoolId = school.id
            gradeLevel = grade
            isActive = true
            isVerified = true
        })

    private fun token(entity: UserEntity): String {
        val body = mockMvc.perform(
            post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"identifier\":\"" + entity.phoneNumber + "\",\"password\":\"password123\"}")
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return objectMapper.readValue(body, AuthResponse::class.java).sessionToken!!
    }

    private fun base() = "/admin/schools/" + school.id

    @BeforeEach
    fun setUp() {
        school = schoolRepository.save(SchoolEntity().apply { name = "Alliance High School"; isActive = true })
        admin = user(Role.TEACHER, "ICT Admin", "0722200001", SubRole.ICT_ADMIN)
        teacherA = user(Role.TEACHER, "Teacher A", "0722200002", SubRole.CTEACHER, "Grade 6")
        teacherB = user(Role.TEACHER, "Teacher B", "0722200003", SubRole.CTEACHER, "Grade 6")
    }

    @Test
    fun `classes are created renamed re-teachered and deleted in the catalogue too`() {
        val t = token(admin)
        val created = objectMapper.readValue(
            mockMvc.perform(
                post(base() + "/classes").header("Authorization", "Bearer " + t)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(CreateAdminClassRequest("Blue", "Grade 6", teacherUserId = teacherA.id.toString()))),
            ).andExpect(status().isOk).andReturn().response.contentAsString,
            AdminClassPayload::class.java,
        )
        check(created.name == "Blue" && created.gradeLevel == "Grade 6")
        check(created.teacherUserId == teacherA.id.toString() && created.teacherName == "Teacher A")
        check(created.studentCount == 0 && created.isActive)

        // Duplicate names in one school are a conflict.
        mockMvc.perform(
            post(base() + "/classes").header("Authorization", "Bearer " + t)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(CreateAdminClassRequest("blue", "Grade 6"))),
        ).andExpect(status().isConflict)

        // The signup catalogue mirrors the live rows.
        val config = objectMapper.readValue(
            mockMvc.perform(get(base() + "/config").header("Authorization", "Bearer " + t))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            SchoolConfigPayload::class.java,
        )
        check(config.classes.orEmpty().any { it.name == "Blue" }) { "catalogue not synced: " + config.classes }

        // Rename + re-grade, then reassign the teacher through the dedicated route.
        val updated = objectMapper.readValue(
            mockMvc.perform(
                put(base() + "/classes/" + created.id).header("Authorization", "Bearer " + t)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(UpdateAdminClassRequest(name = "Blue East", gradeLevel = "Grade 7"))),
            ).andExpect(status().isOk).andReturn().response.contentAsString,
            AdminClassPayload::class.java,
        )
        check(updated.name == "Blue East" && updated.gradeLevel == "Grade 7")
        val reassigned = objectMapper.readValue(
            mockMvc.perform(
                put(base() + "/classes/" + created.id + "/teacher").header("Authorization", "Bearer " + t)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(AssignClassTeacherRequest(teacherB.id.toString()))),
            ).andExpect(status().isOk).andReturn().response.contentAsString,
            AdminClassPayload::class.java,
        )
        check(reassigned.teacherUserId == teacherB.id.toString())

        // A class with learners cannot be deleted; an empty one can.
        classRepository.findById(UUID.fromString(created.id)).orElseThrow().let { row ->
            membershipRepository.save(ClassMembershipEntity().apply {
                classId = row.id
                studentId = user(Role.STUDENT, "Class Learner", "0722200004").id
            })
        }
        mockMvc.perform(delete(base() + "/classes/" + created.id).header("Authorization", "Bearer " + t))
            .andExpect(status().isConflict)
    }

    @Test
    fun `class counts include roster-only learners`() {
        val t = token(admin)
        val created = objectMapper.readValue(
            mockMvc.perform(
                post(base() + "/classes").header("Authorization", "Bearer " + t)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(CreateAdminClassRequest("Gold", "Grade 6", teacherUserId = teacherA.id.toString()))),
            ).andExpect(status().isOk).andReturn().response.contentAsString,
            AdminClassPayload::class.java,
        )
        val classId = UUID.fromString(created.id)
        // Roster-only learners carry provisioned_class_id and deliberately no membership row:
        // the exact population the class teacher's roster and the admin Learners tab show.
        listOf("Roster One" to "0722200101", "Roster Two" to "0722200102").forEach { (name, phone) ->
            userRepository.save(UserEntity().apply {
                this.name = name
                phoneNumber = phone
                this.role = Role.STUDENT
                schoolId = school.id
                gradeLevel = "Grade 6"
                accountKind = AccountKind.ROSTER_ONLY
                provisionedClassId = classId
                provisionedBy = teacherA.id
                isActive = true
            })
        }

        // The admin School Config class list ("Classes & grades") shows them...
        val listed = objectMapper.readValue(
            mockMvc.perform(get(base() + "/classes").header("Authorization", "Bearer " + t))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            Array<AdminClassPayload>::class.java,
        ).single { it.id == created.id }
        check(listed.studentCount == 2) { "admin class count=" + listed.studentCount }

        // ...and so does the teacher's own class list (web and Android read this payload).
        val teacherClasses = objectMapper.readValue(
            mockMvc.perform(get("/teacher/classes").header("Authorization", "Bearer " + token(teacherA)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            Array<TeacherClassPayload>::class.java,
        )
        check(teacherClasses.single { it.classId == created.id }.studentCount == 2) {
            "teacher class count=" + teacherClasses.single { it.classId == created.id }.studentCount
        }

        // A class is not "empty" just because its learners are roster-only.
        mockMvc.perform(delete(base() + "/classes/" + created.id).header("Authorization", "Bearer " + t))
            .andExpect(status().isConflict)
    }

    @Test
    fun `the learner audience read filters by grade class and search`() {
        val t = token(admin)
        val clazz = classRepository.save(TeacherClassEntity().apply {
            teacherUserId = teacherA.id
            schoolId = school.id
            name = "Blue"
            gradeLevel = "Grade 6"
            subject = "Mathematics"
            isActive = true
        })
        val learner = user(Role.STUDENT, "Ann Learner", "0722200010", grade = "Grade 6")
        membershipRepository.save(ClassMembershipEntity().apply { classId = clazz.id; studentId = learner.id })
        user(Role.STUDENT, "Ben Learner", "0722200011", grade = "Grade 7")

        fun students(vararg params: Pair<String, String>): List<AdminStudentPayload> {
            var request = get(base() + "/students")
            params.forEach { (key, value) -> request = request.param(key, value) }
            val body = mockMvc.perform(request.header("Authorization", "Bearer " + t))
                .andExpect(status().isOk).andReturn().response.contentAsString
            return objectMapper.readValue(body, Array<AdminStudentPayload>::class.java).toList()
        }

        val all = students()
        check(all.size == 2) { "unfiltered students=" + all }
        val gradeSix = students("gradeLevel" to "Grade 6")
        check(gradeSix.size == 1 && gradeSix.single().name == "Ann Learner") { "gradeSix=" + gradeSix }
        check(gradeSix.single().classNames == listOf("Blue"))
        check(gradeSix.single().classIds == listOf(clazz.id.toString()))
        check(students("classId" to clazz.id.toString()).size == 1)
        check(students("q" to "ben").single().name == "Ben Learner")
        check(students("gradeLevel" to "Grade 6", "q" to "ben").isEmpty())
    }
}
