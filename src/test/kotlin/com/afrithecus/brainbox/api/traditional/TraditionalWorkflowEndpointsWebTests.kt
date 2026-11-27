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
import com.afrithecus.brainbox.api.traditional.entity.TraditionalCaptureLockEntity
import com.afrithecus.brainbox.api.traditional.entity.TraditionalConfirmationEntity
import com.afrithecus.brainbox.api.traditional.entity.TraditionalExamEntity
import com.afrithecus.brainbox.api.traditional.entity.TraditionalExamSubjectEntity
import com.afrithecus.brainbox.api.traditional.entity.TraditionalMarkEntity
import com.afrithecus.brainbox.api.traditional.model.ExamTerm
import com.afrithecus.brainbox.api.traditional.model.TraditionalExamStatus
import com.afrithecus.brainbox.api.traditional.model.TraditionalSubjectType
import com.afrithecus.brainbox.api.traditional.repository.TraditionalCaptureLockRepository
import com.afrithecus.brainbox.api.traditional.repository.TraditionalConfirmationRepository
import com.afrithecus.brainbox.api.traditional.repository.TraditionalExamRepository
import com.afrithecus.brainbox.api.traditional.repository.TraditionalExamSubjectRepository
import com.afrithecus.brainbox.api.traditional.repository.TraditionalMarkRepository
import com.afrithecus.brainbox.api.traditional.web.SubmissionSummaryDto
import com.afrithecus.brainbox.api.traditional.web.TraditionalExamDto
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
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/** Owner workflow: submissions counter, guarded subset finalisation, audit window and publish gate. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class TraditionalWorkflowEndpointsWebTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val userRepository: UserRepository,
    @Autowired private val schoolRepository: SchoolRepository,
    @Autowired private val classRepository: TeacherClassRepository,
    @Autowired private val membershipRepository: ClassMembershipRepository,
    @Autowired private val examRepository: TraditionalExamRepository,
    @Autowired private val subjectRepository: TraditionalExamSubjectRepository,
    @Autowired private val markRepository: TraditionalMarkRepository,
    @Autowired private val lockRepository: TraditionalCaptureLockRepository,
    @Autowired private val confirmationRepository: TraditionalConfirmationRepository,
    @Autowired private val passwordEncoder: PasswordEncoder,
) {
    private lateinit var school: SchoolEntity
    private lateinit var coordinator: UserEntity
    private lateinit var teacherA: UserEntity
    private lateinit var teacherB: UserEntity
    private lateinit var classA: TeacherClassEntity
    private lateinit var classB: TeacherClassEntity
    private lateinit var exam: TraditionalExamEntity

    private fun teacher(name: String, phone: String, subRole: SubRole) = userRepository.save(UserEntity().apply {
        this.name = name
        phoneNumber = phone
        email = phone + "@workflow.test"
        passwordHash = passwordEncoder.encode("password123") ?: error("encode")
        role = Role.TEACHER
        this.subRole = subRole
        schoolId = school.id
        gradeLevel = "Grade 7"
        isActive = true
        isVerified = true
    })

    private fun learner(index: Int) = userRepository.save(UserEntity().apply {
        name = "Learner $index"
        phoneNumber = "0799000%04d".format(index)
        email = "learner$index@workflow.test"
        passwordHash = passwordEncoder.encode("1234") ?: error("encode")
        role = Role.STUDENT
        schoolId = school.id
        credentialKind = "PIN"
        isActive = true
        isVerified = true
    })

    private fun clazz(name: String, teacher: UserEntity) = classRepository.save(TeacherClassEntity().apply {
        this.name = name
        gradeLevel = "Grade 7"
        subject = "Mathematics"
        teacherUserId = teacher.id
        schoolId = school.id
        isActive = true
    })

    private fun token(user: UserEntity): String {
        val response = mockMvc.perform(
            post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"identifier\":\"" + user.phoneNumber + "\",\"password\":\"password123\"}"),
        ).andReturn().response
        check(response.status == 200) { "login failed " + response.contentAsString }
        return objectMapper.readValue(response.contentAsString, AuthResponse::class.java).sessionToken!!
    }

    private fun perform(method: String, path: String, body: String? = null) =
        mockMvc.perform(
            if (method == "GET") get(path).header("Authorization", "Bearer " + token(coordinator))
            else post(path).header("Authorization", "Bearer " + token(coordinator))
                .contentType(MediaType.APPLICATION_JSON).content(body ?: ""),
        )

    @BeforeEach
    fun setUp() {
        school = schoolRepository.save(SchoolEntity().apply { name = "Workflow School"; isActive = true })
        coordinator = teacher("Workflow Coordinator", "0788700001", SubRole.GRADE_COORDINATOR)
        teacherA = teacher("Teacher A", "0788700002", SubRole.CTEACHER)
        teacherB = teacher("Teacher B", "0788700003", SubRole.CTEACHER)
        classA = clazz("7A", teacherA)
        classB = clazz("7B", teacherB)
        exam = examRepository.save(TraditionalExamEntity().apply {
            title = "Grade 7 Term 2"
            term = ExamTerm.TERM_2
            gradeLevel = "Grade 7"
            year = 2026
            status = TraditionalExamStatus.PRE_FINAL
            maxScore = 100
            autoGenerated = false
            schoolId = school.id
            createdBy = coordinator.id
        })
        subjectRepository.save(TraditionalExamSubjectEntity().apply {
            examId = exam.id
            subjectId = "math"
            name = "Mathematics"
            maxScore = 100
            subjectType = TraditionalSubjectType.SINGLE
        })
        repeat(2) { i ->
            val student = learner(i + 1)
            membershipRepository.save(ClassMembershipEntity().apply { classId = classA.id; studentId = student.id })
            markRepository.save(TraditionalMarkEntity().apply {
                examId = exam.id; studentId = student.id; subjectId = "math"; rawScore = 70 + i; percentage = (70 + i).toDouble()
            })
        }
        // Ten saved-but-unsubmitted learners in 7B: the disregard guard must catch this.
        repeat(10) { i ->
            val student = learner(i + 20)
            membershipRepository.save(ClassMembershipEntity().apply { classId = classB.id; studentId = student.id })
            markRepository.save(TraditionalMarkEntity().apply {
                examId = exam.id; studentId = student.id; subjectId = "math"; rawScore = 50 + i; percentage = (50 + i).toDouble()
            })
        }
        lockRepository.save(TraditionalCaptureLockEntity().apply {
            examId = exam.id; teacherId = teacherA.id; classId = classA.id; classTag = "7A"
        })
        lockRepository.save(TraditionalCaptureLockEntity().apply {
            examId = exam.id; teacherId = teacherB.id; classId = classB.id; classTag = "7B"
        })
        confirmationRepository.save(TraditionalConfirmationEntity().apply {
            examId = exam.id; teacherId = teacherA.id; gradeLevel = "Grade 7"; confirmedAt = Instant.now()
        })
    }

    @Test
    fun `submissions endpoint reports per-class submitted state and scored learners`() {
        val body = perform("GET", "/traditional/exams/" + exam.id + "/submissions")
            .andExpect(status().isOk).andReturn().response.contentAsString
        val dto = objectMapper.readValue(body, SubmissionSummaryDto::class.java)
        val a = dto.classes.first { it.classTag == "7A" }
        val b = dto.classes.first { it.classTag == "7B" }
        check(a.submitted && a.studentsWithMarks == 2) { "7A wrong: $a" }
        check(!b.submitted && b.studentsWithMarks == 10) { "7B wrong: $b" }
    }

    @Test
    fun `a provisioned learner's saved mark counts in the class submissions counter`() {
        val rosterExam = examRepository.save(TraditionalExamEntity().apply {
            title = "Roster-only marks"
            term = ExamTerm.TERM_2
            gradeLevel = "Grade 7"
            year = 2026
            status = TraditionalExamStatus.PRE_FINAL
            maxScore = 100
            autoGenerated = false
            schoolId = school.id
            createdBy = coordinator.id
        })
        subjectRepository.save(TraditionalExamSubjectEntity().apply {
            examId = rosterExam.id
            subjectId = "math"
            name = "Mathematics"
            maxScore = 100
            subjectType = TraditionalSubjectType.SINGLE
        })
        val rosterLearner = userRepository.save(UserEntity().apply {
            name = "Roster Learner"
            phoneNumber = "0799000999"
            email = "learner99@workflow.test"
            passwordHash = passwordEncoder.encode("1234") ?: error("encode")
            role = Role.STUDENT
            schoolId = school.id
            credentialKind = "PIN"
            isActive = true
            isVerified = true
            provisionedClassId = classA.id
        })
        markRepository.save(TraditionalMarkEntity().apply {
            examId = rosterExam.id
            studentId = rosterLearner.id
            subjectId = "math"
            rawScore = 60
            percentage = 60.0
        })
        val body = perform("GET", "/traditional/exams/" + rosterExam.id + "/submissions")
            .andExpect(status().isOk).andReturn().response.contentAsString
        val dto = objectMapper.readValue(body, SubmissionSummaryDto::class.java)
        check(dto.classes.first { it.classTag == "7A" }.studentsWithMarks == 1) { "roster mark was lost: $body" }
    }

    @Test
    fun `capture classes name the other holder even when the caller has a lock`() {
        val body = mockMvc.perform(
            get("/traditional/exams/" + exam.id + "/capture-classes")
                .header("Authorization", "Bearer " + token(teacherA)),
        ).andExpect(status().isOk).andReturn().response.contentAsString
        val row = objectMapper.readTree(body)[0]
        check(row.get("otherClassName")?.asText() == "7B") { "missing other class: $body" }
        check(row.get("otherClassTeacherName")?.asText() == "Teacher B") { "missing holder: $body" }
    }

    @Test
    fun `a class submitted by a coordinator is counted even when its class teacher did not confirm`() {
        confirmationRepository.save(TraditionalConfirmationEntity().apply {
            examId = exam.id; teacherId = coordinator.id; gradeLevel = "7B"; confirmedAt = Instant.now()
        })
        val body = perform("GET", "/traditional/exams/" + exam.id + "/submissions")
            .andExpect(status().isOk).andReturn().response.contentAsString
        val dto = objectMapper.readValue(body, SubmissionSummaryDto::class.java)
        check(dto.classes.first { it.classTag == "7B" }.submitted) { "coordinator submission was lost: $body" }
    }

    @Test
    fun `finalise requires acknowledgement when ignoring a class with ten saved marks`() {
        perform(
            "POST",
            "/traditional/exams/" + exam.id + "/finalize?coordinatorId=" + coordinator.id +
                "&includedClassTags=7A&auditWindowMinutes=30&disregardAcknowledged=false",
        ).andExpect(status().isConflict)
    }

    @Test
    fun `finalise includes only chosen classes archives the rest and opens the audit window`() {
        val body = perform(
            "POST",
            "/traditional/exams/" + exam.id + "/finalize?coordinatorId=" + coordinator.id +
                "&includedClassTags=7A&auditWindowMinutes=15&disregardAcknowledged=true",
        ).andExpect(status().isOk).andReturn().response.contentAsString
        val dto = objectMapper.readValue(body, TraditionalExamDto::class.java)
        check(dto.status == TraditionalExamStatus.FINALIZED)
        check(dto.includedClassTags == listOf("7A")) { "included=" + dto.includedClassTags }
        check(dto.auditDeadlineAt != null && dto.auditDeadlineAt!! > System.currentTimeMillis())
        check(lockRepository.findAllByExamId(exam.id).first { it.classTag == "7B" }.archived)
        check(!lockRepository.findAllByExamId(exam.id).first { it.classTag == "7A" }.archived)
    }

    @Test
    fun `finalise refuses an included class with no scored learners`() {
        perform(
            "POST",
            "/traditional/exams/" + exam.id + "/finalize?coordinatorId=" + coordinator.id +
                "&includedClassTags=7Z&auditWindowMinutes=30&disregardAcknowledged=true",
        ).andExpect(status().isBadRequest)
    }

    @Test
    fun `publish is blocked until the audit window expires then succeeds`() {
        perform("POST", "/traditional/exams/" + exam.id + "/finalize?coordinatorId=" + coordinator.id +
            "&includedClassTags=7A&auditWindowMinutes=30&disregardAcknowledged=true")
            .andExpect(status().isOk)
        perform("POST", "/traditional/exams/" + exam.id + "/publish?coordinatorId=" + coordinator.id)
            .andExpect(status().isConflict)
        val saved = examRepository.findById(exam.id).orElseThrow()
        saved.auditDeadlineAt = Instant.now().minus(1, ChronoUnit.MINUTES)
        examRepository.save(saved)
        val body = perform("POST", "/traditional/exams/" + exam.id + "/publish?coordinatorId=" + coordinator.id)
            .andExpect(status().isOk).andReturn().response.contentAsString
        check(objectMapper.readValue(body, TraditionalExamDto::class.java).status == TraditionalExamStatus.PUBLISHED)
    }

    @Test
    fun `extend never shortens a future deadline and broadcast reaches teachers`() {
        val saved = examRepository.findById(exam.id).orElseThrow()
        saved.status = TraditionalExamStatus.FINALIZED
        saved.auditDeadlineAt = Instant.now().plus(100, ChronoUnit.DAYS)
        examRepository.save(saved)
        val future = saved.auditDeadlineAt!!
        val body = perform("POST", "/traditional/exams/" + exam.id + "/audit-window/extend?minutes=15")
            .andExpect(status().isOk).andReturn().response.contentAsString
        check(objectMapper.readValue(body, TraditionalExamDto::class.java).auditDeadlineAt!! > future.toEpochMilli())
        perform("POST", "/traditional/exams/" + exam.id + "/broadcast", "{\"message\":\"Please review before the window closes\"}")
            .andExpect(status().isNoContent)
    }
}
