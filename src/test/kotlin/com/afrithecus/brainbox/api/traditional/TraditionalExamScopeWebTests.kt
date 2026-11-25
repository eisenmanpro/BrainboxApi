package com.afrithecus.brainbox.api.traditional

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
import com.afrithecus.brainbox.api.traditional.entity.TraditionalEditRequestEntity
import com.afrithecus.brainbox.api.traditional.entity.TraditionalExamEntity
import com.afrithecus.brainbox.api.traditional.entity.TraditionalExamSubjectEntity
import com.afrithecus.brainbox.api.traditional.entity.TraditionalMarkEntity
import com.afrithecus.brainbox.api.traditional.model.ExamTerm
import com.afrithecus.brainbox.api.traditional.model.TraditionalEditStatus
import com.afrithecus.brainbox.api.traditional.model.TraditionalExamStatus
import com.afrithecus.brainbox.api.traditional.model.TraditionalSubjectType
import com.afrithecus.brainbox.api.traditional.repository.TraditionalEditRequestRepository
import com.afrithecus.brainbox.api.traditional.repository.TraditionalExamRepository
import com.afrithecus.brainbox.api.traditional.repository.TraditionalExamSubjectRepository
import com.afrithecus.brainbox.api.traditional.repository.TraditionalMarkRepository
import com.afrithecus.brainbox.api.traditional.web.CaptureSummaryDto
import com.afrithecus.brainbox.api.traditional.web.EditRequestDto
import com.afrithecus.brainbox.api.traditional.web.MarkEntryDto
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
 * Findings "EXAM SESSION": the server, not the UI, enforces who sees what —
 *  - a class-owning non-coordinator only sees their own class in the capture summary
 *    (`ownClassOnly`, scoped totals/classes);
 *  - "remind teachers" is a coordinator action;
 *  - a plain class-owning teacher may request a score edit, a coordinator may not;
 *  - score limits answer an actionable 400.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class TraditionalExamScopeWebTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val userRepository: UserRepository,
    @Autowired private val schoolRepository: SchoolRepository,
    @Autowired private val classRepository: TeacherClassRepository,
    @Autowired private val membershipRepository: ClassMembershipRepository,
    @Autowired private val examRepository: TraditionalExamRepository,
    @Autowired private val subjectRepository: TraditionalExamSubjectRepository,
    @Autowired private val markRepository: TraditionalMarkRepository,
    @Autowired private val editRequestRepository: TraditionalEditRequestRepository,
    @Autowired private val passwordEncoder: PasswordEncoder,
) {
    private lateinit var school: SchoolEntity
    private lateinit var coordinator: UserEntity
    private lateinit var classTeacher: UserEntity
    private lateinit var ownClass: TeacherClassEntity
    private lateinit var otherClass: TeacherClassEntity
    private lateinit var ownLearner: UserEntity
    private lateinit var otherLearner: UserEntity
    private lateinit var exam: TraditionalExamEntity

    private fun teacher(name: String, phone: String, subRole: SubRole?, grade: String?): UserEntity =
        userRepository.save(UserEntity().apply {
            this.name = name
            phoneNumber = phone
            email = phone + "@examscope.test"
            passwordHash = passwordEncoder.encode("password123") ?: error("encode")
            role = Role.TEACHER
            this.subRole = subRole
            schoolId = school.id
            gradeLevel = grade
            isActive = true
            isVerified = true
        })

    private fun learner(name: String, phone: String, grade: String, classId: UUID): UserEntity {
        val entity = userRepository.save(UserEntity().apply {
            this.name = name
            phoneNumber = phone
            email = phone + "@examscope.learner"
            passwordHash = passwordEncoder.encode("password123") ?: error("encode")
            role = Role.STUDENT
            schoolId = school.id
            gradeLevel = grade
            isActive = true
            isVerified = true
        })
        membershipRepository.save(ClassMembershipEntity().apply { this.classId = classId; studentId = entity.id })
        return entity
    }

    private fun token(entity: UserEntity): String {
        val body = mockMvc.perform(
            post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"identifier\":\"" + entity.phoneNumber + "\",\"password\":\"password123\"}")
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return objectMapper.readValue(body, AuthResponse::class.java).sessionToken!!
    }

    private fun summary(token: String): CaptureSummaryDto {
        val body = mockMvc.perform(
            get("/traditional/exams/" + exam.id + "/capture-summary").header("Authorization", "Bearer " + token),
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return objectMapper.readValue(body, CaptureSummaryDto::class.java)
    }

    @BeforeEach
    fun setUp() {
        school = schoolRepository.save(SchoolEntity().apply { name = "Alliance High School"; isActive = true })
        coordinator = teacher("Grade 5 Coordinator", "0755500001", SubRole.GRADE_COORDINATOR, "Grade 5")
        classTeacher = teacher("Class Teacher", "0755500002", null, "Grade 5")
        ownClass = classRepository.save(TeacherClassEntity().apply {
            teacherUserId = classTeacher.id; schoolId = school.id; name = "Buffalo"; gradeLevel = "Grade 5"; subject = "Mathematics"; isActive = true
        })
        otherClass = classRepository.save(TeacherClassEntity().apply {
            teacherUserId = coordinator.id; schoolId = school.id; name = "Lion"; gradeLevel = "Grade 5"; subject = "Mathematics"; isActive = true
        })
        ownLearner = learner("Own Learner", "0755500010", "Grade 5", ownClass.id)
        otherLearner = learner("Other Learner", "0755500011", "Grade 5", otherClass.id)
        exam = examRepository.save(TraditionalExamEntity().apply {
            title = "Grade 5 End Term"
            term = ExamTerm.TERM_2
            gradeLevel = "Grade 5"
            year = 2026
            status = TraditionalExamStatus.CONFIRMED
            maxScore = 100
            autoGenerated = false
            schoolId = school.id
            createdBy = coordinator.id
        })
        subjectRepository.save(TraditionalExamSubjectEntity().apply {
            examId = exam.id
            subjectId = "MAT"
            name = "Mathematics"
            maxScore = 100
            subjectType = TraditionalSubjectType.SINGLE
        })
    }

    @Test
    fun `a class teacher sees only their own class in the capture summary`() {
        val mine = summary(token(classTeacher))
        check(mine.ownClassOnly) { "class teacher summary was not scoped" }
        check(mine.classes.map { it.classTag } == listOf("Buffalo")) { "classes=" + mine.classes.map { it.classTag } }
        check(mine.totalStudents == 1) { "total=" + mine.totalStudents }

        val coordinators = summary(token(coordinator))
        check(!coordinators.ownClassOnly)
        check(coordinators.classes.map { it.classTag }.toSet() == setOf("Buffalo", "Lion"))
        check(coordinators.totalStudents == 2)
    }

    @Test
    fun `only a coordinator can remind teachers`() {
        mockMvc.perform(
            post("/traditional/exams/" + exam.id + "/remind/" + classTeacher.id)
                .header("Authorization", "Bearer " + token(classTeacher)),
        ).andExpect(status().isForbidden)
        mockMvc.perform(
            post("/traditional/exams/" + exam.id + "/remind/" + classTeacher.id)
                .header("Authorization", "Bearer " + token(coordinator)),
        ).andExpect(status().isNoContent)
    }

    @Test
    fun `a plain class-owning teacher can request an edit and both queues see it`() {
        // A stored mark the teacher is asking to correct (oldScore must match it).
        markRepository.save(TraditionalMarkEntity().apply {
            examId = exam.id
            studentId = ownLearner.id
            subjectId = "MAT"
            rawScore = 40
            percentage = 40.0
            confirmedByTeacher = true
        })
        val body = mapOf(
            "id" to "er_" + UUID.randomUUID(),
            "examId" to exam.id.toString(),
            "teacherId" to "",
            "studentId" to ownLearner.id.toString(),
            "subjectId" to "MAT",
            "oldScore" to 40,
            "newScore" to 55,
            "reason" to "Mark captured against the wrong learner",
            "status" to "PENDING",
            "createdAt" to System.currentTimeMillis(),
        )
        val teacherToken = token(classTeacher)
        val created = objectMapper.readValue(
            mockMvc.perform(
                post("/traditional/exams/" + exam.id + "/edit-requests")
                    .header("Authorization", "Bearer " + teacherToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(body)),
            ).andExpect(status().isOk).andReturn().response.contentAsString,
            EditRequestDto::class.java,
        )
        check(created.status == TraditionalEditStatus.PENDING) { "status=" + created.status }

        // The request is visible to the teacher's own queue and the coordinator's queue.
        fun queue(token: String): List<EditRequestDto> {
            val json = mockMvc.perform(get("/traditional/edit-requests").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk).andReturn().response.contentAsString
            return objectMapper.readValue(json, Array<EditRequestDto>::class.java).toList()
        }
        check(queue(teacherToken).any { it.id == created.id })
        check(queue(token(coordinator)).any { it.id == created.id })
    }

    @Test
    fun `a score above the subject limit answers an actionable bad request`() {
        // The class teacher may capture the OTHER class (F7 only forbids their own class).
        val entry = MarkEntryDto(
            studentId = otherLearner.id.toString(),
            subjectId = "MAT",
            rawScore = 120,
            classId = otherClass.id.toString(),
        )
        val response = mockMvc.perform(
            post("/traditional/exams/" + exam.id + "/marks")
                .header("Authorization", "Bearer " + token(classTeacher))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(listOf(entry))),
        ).andExpect(status().isBadRequest).andReturn().response.contentAsString
        check(response.contains("Mathematics")) { "message=" + response }
        check(response.contains("between 0 and 100")) { "message=" + response }
        check(response.contains("120")) { "message=" + response }
    }

    @Test
    fun `an ordinary class teacher is locked to one class per capture session`() {
        val teacherToken = token(classTeacher)
        val lion = MarkEntryDto(otherLearner.id.toString(), "MAT", 55, classId = otherClass.id.toString())
        mockMvc.perform(
            post("/traditional/exams/" + exam.id + "/marks")
                .header("Authorization", "Bearer " + teacherToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(listOf(lion))),
        ).andExpect(status().isOk)

        // The lock is surfaced so the client can restore it after a refresh.
        val classesJson = mockMvc.perform(
            get("/traditional/exams/" + exam.id + "/capture-classes")
                .header("Authorization", "Bearer " + teacherToken),
        ).andExpect(status().isOk).andReturn().response.contentAsString
        val classes = objectMapper.readTree(classesJson)
        check(classes.any { it.get("lockedClassName")?.asString() == "Lion" }) { "classes=" + classesJson }

        // A third class in the same grade is refused while the session is locked to Lion.
        val otherOwner = teacher("Other Class Owner", "0755500013", null, "Grade 5")
        val tiger = classRepository.save(TeacherClassEntity().apply {
            teacherUserId = otherOwner.id; schoolId = school.id; name = "Tiger"; gradeLevel = "Grade 5"; subject = "Mathematics"; isActive = true
        })
        val tigerLearner = learner("Tiger Learner", "0755500012", "Grade 5", tiger.id)
        val switched = MarkEntryDto(tigerLearner.id.toString(), "MAT", 60, classId = tiger.id.toString())
        val refused = mockMvc.perform(
            post("/traditional/exams/" + exam.id + "/marks")
                .header("Authorization", "Bearer " + teacherToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(listOf(switched))),
        ).andExpect(status().isConflict).andReturn().response.contentAsString
        check(refused.contains("locked")) { "message=" + refused }

        // A coordinator also captures another class, never their own (Lion is theirs).
        val coordinatorToken = token(coordinator)
        mockMvc.perform(
            post("/traditional/exams/" + exam.id + "/marks")
                .header("Authorization", "Bearer " + coordinatorToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(listOf(lion))),
        ).andExpect(status().isForbidden)
        mockMvc.perform(
            post("/traditional/exams/" + exam.id + "/marks")
                .header("Authorization", "Bearer " + coordinatorToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(listOf(switched))),
        ).andExpect(status().isOk)
    }

    @Test
    fun `class binding prevents two teachers sharing a class and skips the caller's own class`() {
        val otherOwner = teacher("Bind Owner", "0755500014", null, "Grade 5")
        exam.status = TraditionalExamStatus.IN_PROGRESS
        examRepository.save(exam)
        val tiger = classRepository.save(TeacherClassEntity().apply {
            teacherUserId = otherOwner.id; schoolId = school.id; name = "Tiger"; gradeLevel = "Grade 5"; subject = "Mathematics"; isActive = true
        })
        val coordinatorToken = token(coordinator)
        // Coordinators cannot bind their own class (Lion).
        mockMvc.perform(
            post("/traditional/exams/" + exam.id + "/capture-class")
                .header("Authorization", "Bearer " + coordinatorToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"classId\":\"" + otherClass.id + "\"}"),
        ).andExpect(status().isForbidden)

        // They can bind another class; the second teacher then sees the conflict with a name.
        mockMvc.perform(
            post("/traditional/exams/" + exam.id + "/capture-class")
                .header("Authorization", "Bearer " + coordinatorToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"classId\":\"" + tiger.id + "\"}"),
        ).andExpect(status().isOk)
        val conflict = mockMvc.perform(
            post("/traditional/exams/" + exam.id + "/capture-class")
                .header("Authorization", "Bearer " + token(classTeacher))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"classId\":\"" + tiger.id + "\"}"),
        ).andExpect(status().isConflict).andReturn().response.contentAsString
        check(conflict.contains("Grade 5 Coordinator")) { "conflict must name the holder: " + conflict }

        // The captured class list hides the class held by the coordinator.
        val classes = objectMapper.readTree(
            mockMvc.perform(
                get("/traditional/exams/" + exam.id + "/capture-classes")
                    .header("Authorization", "Bearer " + token(classTeacher)),
            ).andExpect(status().isOk).andReturn().response.contentAsString,
        )
        check(classes.none { it.get("name")?.asString() == "Tiger" && it.get("allowed")?.asBoolean() == true }) { "taken class still selectable: " + classes }
    }


    @Test
    fun `a coordinator correction after finalization reopens the session`() {
        exam.status = TraditionalExamStatus.FINALIZED
        exam.finalizedAt = java.time.Instant.now()
        exam.finalizedBy = coordinator.id
        examRepository.save(exam)
        val corrected = MarkEntryDto(otherLearner.id.toString(), "MAT", 88, classId = otherClass.id.toString())
        mockMvc.perform(
            post("/traditional/exams/" + exam.id + "/marks")
                .header("Authorization", "Bearer " + token(coordinator))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(listOf(corrected))),
        ).andExpect(status().isOk)
        val reloaded = examRepository.findById(exam.id).orElseThrow()
        check(reloaded.status == TraditionalExamStatus.IN_PROGRESS) { "coordinator edit must reopen capture, got " + reloaded.status }
        check(reloaded.finalizedAt == null && reloaded.finalizedBy == null) { "old finalization must not survive the correction" }
    }

}
