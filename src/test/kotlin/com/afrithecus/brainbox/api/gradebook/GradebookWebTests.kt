package com.afrithecus.brainbox.api.gradebook

import com.afrithecus.brainbox.api.auth.web.AuthResponse
import com.afrithecus.brainbox.api.classes.entity.ClassMembershipEntity
import com.afrithecus.brainbox.api.classes.entity.TeacherClassEntity
import com.afrithecus.brainbox.api.classes.repository.ClassMembershipRepository
import com.afrithecus.brainbox.api.classes.repository.TeacherClassRepository
import com.afrithecus.brainbox.api.gradebook.repository.GradebookAssessmentRepository
import com.afrithecus.brainbox.api.gradebook.repository.GradebookEntryRepository
import com.afrithecus.brainbox.api.gradebook.web.GradebookAssessmentPayload
import com.afrithecus.brainbox.api.gradebook.web.GradebookEntryPayload
import com.afrithecus.brainbox.api.gradebook.web.PublishedGradePayload
import com.afrithecus.brainbox.api.identity.entity.SchoolEntity
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.model.Role
import com.afrithecus.brainbox.api.identity.model.SubRole
import com.afrithecus.brainbox.api.identity.repository.SchoolRepository
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import com.afrithecus.brainbox.api.traditional.model.ExamTerm
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
 * Gradebook (docs/ongoing/api_gradebook_changes.md): client-id idempotency for
 * assessments and (class, assessment, student) idempotency for grades, plus class
 * ownership and author/coordinator assessment rights.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class GradebookWebTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val userRepository: UserRepository,
    @Autowired private val schoolRepository: SchoolRepository,
    @Autowired private val classRepository: TeacherClassRepository,
    @Autowired private val membershipRepository: ClassMembershipRepository,
    @Autowired private val assessmentRepository: GradebookAssessmentRepository,
    @Autowired private val entryRepository: GradebookEntryRepository,
    @Autowired private val passwordEncoder: PasswordEncoder,
) {

    private lateinit var schoolId: UUID

    @BeforeEach
    fun setUp() {
        val school = SchoolEntity()
        school.name = "Alliance High School"
        school.isActive = true
        schoolRepository.save(school)
        schoolId = school.id
    }

    private fun user(role: Role, name: String, phone: String, subRole: SubRole? = null): UserEntity {
        val entity = UserEntity()
        entity.phoneNumber = phone
        entity.email = phone + "@gradebook.test"
        entity.passwordHash = passwordEncoder.encode("password123") ?: error("encode")
        entity.name = name
        entity.role = role
        entity.subRole = subRole
        entity.schoolId = schoolId
        entity.isVerified = true
        entity.isActive = true
        return userRepository.save(entity)
    }

    private fun teacherClass(owner: UserEntity, name: String): TeacherClassEntity {
        val clazz = TeacherClassEntity()
        clazz.teacherUserId = owner.id
        clazz.schoolId = schoolId
        clazz.name = name
        clazz.gradeLevel = "Grade 7"
        clazz.subject = "Mathematics"
        clazz.isActive = true
        return classRepository.save(clazz)
    }

    private fun enroll(clazz: TeacherClassEntity, student: UserEntity) {
        membershipRepository.save(ClassMembershipEntity().apply {
            classId = clazz.id
            studentId = student.id
        })
    }

    private fun token(user: UserEntity): String {
        val login = mockMvc.perform(
            post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"identifier\":\"${user.email}\",\"password\":\"password123\"}")
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return objectMapper.readValue(login, AuthResponse::class.java).sessionToken!!
    }

    private fun auth(token: String) = "Bearer " + token

    private fun assessment(id: String, classId: String, type: String = "QUIZ", term: ExamTerm = ExamTerm.TERM_1, published: Boolean = false): GradebookAssessmentPayload =
        GradebookAssessmentPayload(
            id = id,
            classId = classId,
            title = "Pop quiz",
            assessmentType = type,
            maxScore = 50,
            dateAssigned = System.currentTimeMillis(),
            term = term,
            isPublished = published,
            countsTowardAverage = type != "CONTEST",
        )

    private fun entry(id: String, classId: String, assessmentId: String, student: UserEntity, raw: Int, teacher: UserEntity): GradebookEntryPayload =
        GradebookEntryPayload(
            id = id,
            classId = classId,
            teacherId = teacher.id.toString(),
            assessmentId = assessmentId,
            assessmentType = "QUIZ",
            studentId = student.id.toString(),
            studentName = student.name,
            rawScore = raw,
            maxScore = 50,
            percentage = raw * 100 / 50,
            assessmentTitle = "Pop quiz",
            gradedAt = System.currentTimeMillis(),
        )

    @Test
    fun `assessment and grade upserts are idempotent and ownership-gated`() {
        val alice = user(Role.STUDENT, "Alice Mwangi", "0733000101")
        val bob = user(Role.STUDENT, "Bob Otieno", "0733000102")
        val teacher = user(Role.TEACHER, "Class Teacher", "0733000103", subRole = SubRole.CTEACHER)
        val other = user(Role.TEACHER, "Other Teacher", "0733000104", subRole = SubRole.CTEACHER)
        val coordinator = user(Role.TEACHER, "Coordinator", "0733000105", subRole = SubRole.GRADE_COORDINATOR)
        val clazz = teacherClass(teacher, "Grade 7 North")
        enroll(clazz, alice)
        enroll(clazz, bob)

        val teacherToken = token(teacher)
        val otherToken = token(other)
        val coordinatorToken = token(coordinator)

        // Create + idempotent re-create with the same client id.
        val body = objectMapper.writeValueAsString(assessment("assess_1", clazz.id.toString()))
        val created = objectMapper.readValue(
            mockMvc.perform(
                post("/teacher/classes/${clazz.id}/assessments").header("Authorization", auth(teacherToken))
                    .contentType(MediaType.APPLICATION_JSON).content(body)
            ).andExpect(status().isOk).andReturn().response.contentAsString,
            GradebookAssessmentPayload::class.java,
        )
        check(created.id == "assess_1")
        check(created.createdBy == teacher.id.toString())
        mockMvc.perform(
            post("/teacher/classes/${clazz.id}/assessments").header("Authorization", auth(teacherToken))
                .contentType(MediaType.APPLICATION_JSON).content(body)
        ).andExpect(status().isOk)
        check(assessmentRepository.findAllByClassIdOrderByDateAssignedDesc(clazz.id).size == 1)

        // Grade upsert keyed on (class, assessment, student): a replayed grade with a
        // new client id updates the existing row instead of duplicating it.
        mockMvc.perform(
            post("/teacher/gradebook").header("Authorization", auth(teacherToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(entry("gb_1", clazz.id.toString(), "assess_1", alice, 36, teacher)))
        ).andExpect(status().isOk)
        val replayed = objectMapper.readValue(
            mockMvc.perform(
                post("/teacher/gradebook").header("Authorization", auth(teacherToken))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(entry("gb_2", clazz.id.toString(), "assess_1", alice, 40, teacher)))
            ).andExpect(status().isOk).andReturn().response.contentAsString,
            GradebookEntryPayload::class.java,
        )
        check(replayed.id == "gb_1")
        check(replayed.rawScore == 40)
        check(replayed.percentage == 80)
        check(replayed.assessmentTitle == "Pop quiz")
        check(entryRepository.findAllByClassId(clazz.id).size == 1)

        // Bulk + update + delete.
        mockMvc.perform(
            post("/teacher/gradebook/bulk").header("Authorization", auth(teacherToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(listOf(entry("gb_3", clazz.id.toString(), "assess_1", bob, 25, teacher))))
        ).andExpect(status().isOk)
        val updated = objectMapper.readValue(
            mockMvc.perform(
                put("/teacher/gradebook/gb_1").header("Authorization", auth(teacherToken))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(entry("gb_1", clazz.id.toString(), "assess_1", alice, 45, teacher)))
            ).andExpect(status().isOk).andReturn().response.contentAsString,
            GradebookEntryPayload::class.java,
        )
        check(updated.rawScore == 45)
        check(updated.percentage == 90)
        mockMvc.perform(delete("/teacher/gradebook/gb_3").header("Authorization", auth(teacherToken)))
            .andExpect(status().isNoContent)
        check(entryRepository.findAllByClassId(clazz.id).size == 1)

        // Only the class owner (or coordinator) may write; another teacher is forbidden.
        mockMvc.perform(
            post("/teacher/classes/${clazz.id}/assessments").header("Authorization", auth(otherToken))
                .contentType(MediaType.APPLICATION_JSON).content(body)
        ).andExpect(status().isForbidden)
        mockMvc.perform(
            post("/teacher/classes/${clazz.id}/assessments").header("Authorization", auth(coordinatorToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(assessment("assess_2", clazz.id.toString(), type = "CONTEST")))
        ).andExpect(status().isOk)

        // Deleting an assessment removes its entries.
        mockMvc.perform(delete("/teacher/classes/${clazz.id}/assessments/assess_1").header("Authorization", auth(teacherToken)))
            .andExpect(status().isNoContent)
        check(assessmentRepository.findByClientId("assess_1") == null)
        check(entryRepository.findAllByClassId(clazz.id).isEmpty())
    }

    @Test
    fun `published grades gate by assessment and band server-side`() {
        val alice = user(Role.STUDENT, "Alice Mwangi", "0733000201")
        val parent = user(Role.PARENT, "Parent One", "0733000202")
        val stranger = user(Role.PARENT, "Other Parent", "0733000203")
        alice.parentUserId = parent.id
        userRepository.save(alice)
        val teacher = user(Role.TEACHER, "Class Teacher", "0733000204", subRole = SubRole.CTEACHER)
        val clazz = teacherClass(teacher, "Grade 8 South")
        enroll(clazz, alice)

        val teacherToken = token(teacher)
        val aliceToken = token(alice)
        val parentToken = token(parent)
        val strangerToken = token(stranger)

        mockMvc.perform(
            post("/teacher/classes/${clazz.id}/assessments").header("Authorization", auth(teacherToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(assessment("assess_pub", clazz.id.toString(), published = false)))
        ).andExpect(status().isOk)
        mockMvc.perform(
            post("/teacher/gradebook").header("Authorization", auth(teacherToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(entry("gb_pub", clazz.id.toString(), "assess_pub", alice, 45, teacher)))
        ).andExpect(status().isOk)

        // Draft: nothing reaches the learner.
        check(
            objectMapper.readValue(
                mockMvc.perform(get("/student/grades").header("Authorization", auth(aliceToken)))
                    .andExpect(status().isOk).andReturn().response.contentAsString,
                Array<PublishedGradePayload>::class.java,
            ).isEmpty()
        )

        // Publish.
        mockMvc.perform(
            put("/teacher/classes/${clazz.id}/assessments/assess_pub").header("Authorization", auth(teacherToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(assessment("assess_pub", clazz.id.toString(), published = true)))
        ).andExpect(status().isOk)

        val published = objectMapper.readValue(
            mockMvc.perform(get("/student/grades").header("Authorization", auth(aliceToken)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            Array<PublishedGradePayload>::class.java,
        )
        check(published.size == 1)
        check(published.single().assessmentTitle == "Pop quiz")
        check(published.single().term == "TERM_1")
        check(published.single().percentage == 90)
        check(published.single().gradeBand == "EE")
        check(published.single().countsTowardAverage)

        // Term filter.
        check(
            objectMapper.readValue(
                mockMvc.perform(get("/student/grades").param("term", "TERM_2").header("Authorization", auth(aliceToken)))
                    .andExpect(status().isOk).andReturn().response.contentAsString,
                Array<PublishedGradePayload>::class.java,
            ).isEmpty()
        )

        // Linked parent sees it; an unlinked parent is forbidden.
        val parentView = objectMapper.readValue(
            mockMvc.perform(get("/parent/child/${alice.id}/grades").header("Authorization", auth(parentToken)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            Array<PublishedGradePayload>::class.java,
        )
        check(parentView.size == 1)
        mockMvc.perform(get("/parent/child/${alice.id}/grades").header("Authorization", auth(strangerToken)))
            .andExpect(status().isForbidden)

        // A published contest is returned but flagged as not counting toward the average.
        mockMvc.perform(
            post("/teacher/classes/${clazz.id}/assessments").header("Authorization", auth(teacherToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(assessment("assess_contest", clazz.id.toString(), type = "CONTEST", term = ExamTerm.TERM_2, published = true)))
        ).andExpect(status().isOk)
        mockMvc.perform(
            post("/teacher/gradebook").header("Authorization", auth(teacherToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(entry("gb_contest", clazz.id.toString(), "assess_contest", alice, 30, teacher)))
        ).andExpect(status().isOk)
        val withContest = objectMapper.readValue(
            mockMvc.perform(get("/student/grades").header("Authorization", auth(aliceToken)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            Array<PublishedGradePayload>::class.java,
        )
        check(withContest.size == 2)
        check(withContest.first { it.assessmentId == "assess_contest" }.countsTowardAverage.not())

        // Unpublishing hides it again.
        mockMvc.perform(
            put("/teacher/classes/${clazz.id}/assessments/assess_pub").header("Authorization", auth(teacherToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(assessment("assess_pub", clazz.id.toString(), published = false)))
        ).andExpect(status().isOk)
        val afterUnpublish = objectMapper.readValue(
            mockMvc.perform(get("/student/grades").header("Authorization", auth(aliceToken)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            Array<PublishedGradePayload>::class.java,
        )
        check(afterUnpublish.none { it.assessmentId == "assess_pub" })
    }
}

