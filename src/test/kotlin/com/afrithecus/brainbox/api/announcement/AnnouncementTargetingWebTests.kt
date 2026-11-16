package com.afrithecus.brainbox.api.announcement

import com.afrithecus.brainbox.api.announcement.web.SchoolAnnouncementPayload
import com.afrithecus.brainbox.api.announcement.web.TeacherAnnouncementPayload
import com.afrithecus.brainbox.api.auth.web.AuthResponse
import com.afrithecus.brainbox.api.classes.entity.ClassMembershipEntity
import com.afrithecus.brainbox.api.classes.entity.TeacherClassEntity
import com.afrithecus.brainbox.api.classes.repository.ClassMembershipRepository
import com.afrithecus.brainbox.api.classes.repository.TeacherClassRepository
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.util.UUID

/**
 * Structured announcement targeting (findings "ADMIN ANNOUNCEMENTS 1"): the admin picks
 * grades/classes/individual teachers or learners, the server resolves the recipients, and the
 * delivered copy appears in each recipient's existing feed — a targeted announcement must not
 * reach anyone outside the target.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AnnouncementTargetingWebTests(
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
    private lateinit var grade5Teacher: UserEntity
    private lateinit var grade6Teacher: UserEntity
    private lateinit var learner5: UserEntity
    private lateinit var learner6: UserEntity
    private lateinit var class5: TeacherClassEntity
    private lateinit var class6: TeacherClassEntity

    private fun user(
        role: Role,
        name: String,
        phone: String,
        subRole: SubRole? = null,
        grade: String? = null,
    ): UserEntity = userRepository.save(UserEntity().apply {
        this.name = name
        phoneNumber = phone
        email = phone + "@anntarget.test"
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

    private fun announce(token: String, payload: SchoolAnnouncementPayload): SchoolAnnouncementPayload =
        objectMapper.readValue(
            mockMvc.perform(
                post("/admin/schools/" + school.id + "/announcements")
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(payload)),
            ).andExpect(status().isOk).andReturn().response.contentAsString,
            SchoolAnnouncementPayload::class.java,
        )

    private fun teacherFeed(token: String): List<TeacherAnnouncementPayload> {
        val body = mockMvc.perform(get("/teacher/announcements/received").header("Authorization", "Bearer " + token))
            .andExpect(status().isOk).andReturn().response.contentAsString
        return objectMapper.readValue(body, Array<TeacherAnnouncementPayload>::class.java).toList()
    }

    private fun learnerFeed(token: String): List<TeacherAnnouncementPayload> {
        val body = mockMvc.perform(get("/announcements/for-me").header("Authorization", "Bearer " + token))
            .andExpect(status().isOk).andReturn().response.contentAsString
        return objectMapper.readValue(body, Array<TeacherAnnouncementPayload>::class.java).toList()
    }

    private fun request(title: String, targetType: String, grades: List<String> = emptyList(), classes: List<String> = emptyList(), teachers: List<String> = emptyList(), students: List<String> = emptyList()) =
        SchoolAnnouncementPayload(
            announcementId = "",
            title = title,
            body = "Body for " + title,
            audience = "",
            targetType = targetType,
            targetGradeLevels = grades,
            targetClassIds = classes,
            targetTeacherIds = teachers,
            targetStudentIds = students,
        )

    @BeforeEach
    fun setUp() {
        school = schoolRepository.save(SchoolEntity().apply { name = "Alliance High School"; isActive = true })
        admin = user(Role.TEACHER, "ICT Admin", "0744400001", SubRole.ICT_ADMIN)
        grade5Teacher = user(Role.TEACHER, "Grade Five Teacher", "0744400002", SubRole.CTEACHER, "Grade 5")
        grade6Teacher = user(Role.TEACHER, "Grade Six Teacher", "0744400003", SubRole.CTEACHER, "Grade 6")
        class5 = classRepository.save(TeacherClassEntity().apply {
            teacherUserId = grade5Teacher.id; schoolId = school.id; name = "Buffalo"; gradeLevel = "Grade 5"; subject = "Mathematics"; isActive = true
        })
        class6 = classRepository.save(TeacherClassEntity().apply {
            teacherUserId = grade6Teacher.id; schoolId = school.id; name = "Lion"; gradeLevel = "Grade 6"; subject = "Mathematics"; isActive = true
        })
        learner5 = user(Role.STUDENT, "Ann Grade Five", "0744400010", grade = "Grade 5")
        learner6 = user(Role.STUDENT, "Ben Grade Six", "0744400011", grade = "Grade 6")
        membershipRepository.save(ClassMembershipEntity().apply { classId = class5.id; studentId = learner5.id })
        membershipRepository.save(ClassMembershipEntity().apply { classId = class6.id; studentId = learner6.id })
    }

    @Test
    fun `a grade target reaches only that grade's teachers and learners`() {
        val t = token(admin)
        val created = announce(t, request("Grade five assembly", "GRADE", grades = listOf("Grade 5")))
        check(created.targetType == "GRADE" && created.targetGradeLevels == listOf("Grade 5"))
        check(created.estimatedReach == 1) { "reach=" + created.estimatedReach }

        check(teacherFeed(token(grade5Teacher)).any { it.title == "Grade five assembly" })
        check(teacherFeed(token(grade6Teacher)).none { it.title == "Grade five assembly" })
        check(learnerFeed(token(learner5)).any { it.title == "Grade five assembly" })
        check(learnerFeed(token(learner6)).none { it.title == "Grade five assembly" })
    }

    @Test
    fun `class individuals and teacher targets are honoured and deletes remove the delivered copy`() {
        val t = token(admin)
        announce(t, request("Buffalo only", "CLASS", classes = listOf(class5.id.toString())))
        check(learnerFeed(token(learner5)).any { it.title == "Buffalo only" })
        check(learnerFeed(token(learner6)).none { it.title == "Buffalo only" })

        announce(t, request("One learner", "INDIVIDUALS", students = listOf(learner6.id.toString())))
        check(learnerFeed(token(learner6)).any { it.title == "One learner" })
        check(learnerFeed(token(learner5)).none { it.title == "One learner" })
        check(teacherFeed(token(grade6Teacher)).none { it.title == "One learner" }) { "staff copy leaked a learner target" }

        val staff = announce(t, request("Staff briefing", "TEACHERS", teachers = listOf(grade5Teacher.id.toString())))
        check(teacherFeed(token(grade5Teacher)).any { it.title == "Staff briefing" })
        check(teacherFeed(token(grade6Teacher)).none { it.title == "Staff briefing" })

        mockMvc.perform(
            delete("/admin/schools/" + school.id + "/announcements/" + staff.announcementId)
                .header("Authorization", "Bearer " + t),
        ).andExpect(status().isNoContent)
        check(teacherFeed(token(grade5Teacher)).none { it.title == "Staff briefing" }) { "delete left the delivered copy" }
    }

    @Test
    fun `targeting is validated and legacy audience payloads still work`() {
        val t = token(admin)
        mockMvc.perform(
            post("/admin/schools/" + school.id + "/announcements").header("Authorization", "Bearer " + t)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request("No class", "CLASS"))),
        ).andExpect(status().isBadRequest)
        val otherSchool = schoolRepository.save(SchoolEntity().apply { name = "Other School"; isActive = true })
        val foreignClass = classRepository.save(TeacherClassEntity().apply {
            teacherUserId = grade5Teacher.id; schoolId = otherSchool.id; name = "Elsewhere"; gradeLevel = "Grade 5"; subject = "Math"; isActive = true
        })
        mockMvc.perform(
            post("/admin/schools/" + school.id + "/announcements").header("Authorization", "Bearer " + t)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request("Foreign class", "CLASS", classes = listOf(foreignClass.id.toString())))),
        ).andExpect(status().isBadRequest)

        // Legacy free-text audience only: accepted, reach from the roster, no target fields.
        val legacy = announce(
            t,
            SchoolAnnouncementPayload(announcementId = "", title = "Legacy all", body = "Body", audience = "All"),
        )
        check(legacy.targetType == null)
        check(legacy.estimatedReach == 5) { "legacy reach=" + legacy.estimatedReach }
    }
}
