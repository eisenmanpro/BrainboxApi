package com.afrithecus.brainbox.api.traditional

import com.afrithecus.brainbox.api.auth.web.AuthResponse
import com.afrithecus.brainbox.api.classes.entity.TeacherClassEntity
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
import com.afrithecus.brainbox.api.traditional.web.EditRequestDto
import com.afrithecus.brainbox.api.traditional.web.StudentGradeRowDto
import com.afrithecus.brainbox.api.traditional.web.TraditionalCoordinatorSummaryDto
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
 * The coordinator queue endpoints the web calls but the API never exposed:
 * `GET /traditional/edit-requests` (+ its server-side `/summary` count, CD-06) and
 * `GET /traditional/exams/{examId}/grade-table`.
 *
 * Scoping is the point of these tests: a coordinator only ever sees their assigned grade,
 * an unassigned coordinator sees nothing, a plain teacher only their own requests, and the
 * ICT admin the whole school.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class TraditionalCoordinatorEndpointsWebTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val userRepository: UserRepository,
    @Autowired private val schoolRepository: SchoolRepository,
    @Autowired private val classRepository: TeacherClassRepository,
    @Autowired private val examRepository: TraditionalExamRepository,
    @Autowired private val subjectRepository: TraditionalExamSubjectRepository,
    @Autowired private val markRepository: TraditionalMarkRepository,
    @Autowired private val editRequestRepository: TraditionalEditRequestRepository,
    @Autowired private val passwordEncoder: PasswordEncoder,
) {

    private lateinit var school: SchoolEntity
    private lateinit var otherSchool: SchoolEntity
    private lateinit var coordinator: UserEntity
    private lateinit var unassigned: UserEntity
    private lateinit var teacher: UserEntity
    private lateinit var admin: UserEntity
    private lateinit var learner: UserEntity
    private lateinit var examGrade7: TraditionalExamEntity
    private lateinit var examGrade8: TraditionalExamEntity
    private lateinit var teacherRequest: TraditionalEditRequestEntity
    private lateinit var coordinatorRequest: TraditionalEditRequestEntity

    private fun user(
        name: String,
        phone: String,
        schoolId: UUID,
        subRole: SubRole? = null,
        grade: String? = null,
    ): UserEntity = userRepository.save(UserEntity().apply {
        phoneNumber = phone
        email = phone + "@coordept.test"
        passwordHash = passwordEncoder.encode("password123") ?: error("encode")
        this.name = name
        role = Role.TEACHER
        this.subRole = subRole
        this.schoolId = schoolId
        gradeLevel = grade
        isActive = true
        isVerified = true
    })

    /** A real learner row: `traditional_edit_requests.student_id` is an FK to users. */
    private fun student(name: String, phone: String): UserEntity = userRepository.save(UserEntity().apply {
        this.name = name
        phoneNumber = phone
        email = phone + "@coordept.learner"
        passwordHash = passwordEncoder.encode("1234") ?: error("encode")
        role = Role.STUDENT
        schoolId = school.id
        credentialKind = "PIN"
        isActive = true
        isVerified = true
    })

    private fun ownedClass(teacherId: UUID, grade: String, name: String) = classRepository.save(
        TeacherClassEntity().apply {
            teacherUserId = teacherId
            schoolId = school.id
            this.name = name
            gradeLevel = grade
            subject = "Mathematics"
            isActive = true
        },
    )

    private fun exam(grade: String, title: String): TraditionalExamEntity = examRepository.save(
        TraditionalExamEntity().apply {
            this.title = title
            term = ExamTerm.TERM_2
            gradeLevel = grade
            year = 2025
            status = TraditionalExamStatus.CONFIRMED
            maxScore = 100
            autoGenerated = false
            schoolId = school.id
            createdBy = coordinator.id
        },
    )

    private fun request(
        exam: TraditionalExamEntity,
        requester: UserEntity,
        status: TraditionalEditStatus = TraditionalEditStatus.PENDING,
    ): TraditionalEditRequestEntity = editRequestRepository.save(
        TraditionalEditRequestEntity().apply {
            examId = exam.id
            requesterId = requester.id
            studentId = learner.id
            subjectId = "math"
            oldScore = 40
            newScore = 65
            reason = "Mark entered against the wrong learner"
            this.status = status
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

    private fun requests(path: String, token: String): List<EditRequestDto> {
        val body = mockMvc.perform(get(path).header("Authorization", "Bearer " + token))
            .andExpect(status().isOk).andReturn().response.contentAsString
        return objectMapper.readValue(body, Array<EditRequestDto>::class.java).toList()
    }

    @BeforeEach
    fun setUp() {
        school = schoolRepository.save(SchoolEntity().apply { name = "Alliance High School"; isActive = true })
        otherSchool = schoolRepository.save(SchoolEntity().apply { name = "Moi Avenue School"; isActive = true })
        coordinator = user("Grade 7 Coordinator", "0788800001", school.id, SubRole.GRADE_COORDINATOR, "Grade 7")
        unassigned = user("Unassigned Coordinator", "0788800002", school.id, SubRole.GRADE_COORDINATOR, null)
        teacher = user("Class Teacher", "0788800003", school.id, SubRole.CTEACHER, "Grade 7")
        admin = user("ICT Admin", "0788800004", school.id, SubRole.ICT_ADMIN)
        learner = student("Queue Learner", "0788800009")
        ownedClass(coordinator.id, "Grade 7", "Grade 7 West")
        ownedClass(teacher.id, "Grade 7", "Grade 7 East")
        examGrade7 = exam("Grade 7", "Grade 7 End Term 2")
        examGrade8 = exam("Grade 8", "Grade 8 End Term 2")
        teacherRequest = request(examGrade7, teacher)
        coordinatorRequest = request(examGrade7, coordinator)
        // A request in another grade must never reach the Grade 7 coordinator.
        request(examGrade8, unassigned)
    }

    @Test
    fun `edit requests are scoped to the coordinator's grade`() {
        val t = token(coordinator)
        val rows = requests("/traditional/edit-requests", t)
        check(rows.size == 2) { "coordinator saw " + rows.map { it.id } }
        check(rows.map { it.id }.toSet() == setOf(teacherRequest.id.toString(), coordinatorRequest.id.toString()))
        check(rows.all { it.status == TraditionalEditStatus.PENDING })

        // Status filter narrows without widening the scope.
        editRequestRepository.save(coordinatorRequest.apply { status = TraditionalEditStatus.APPROVED })
        val pendingOnly = requests("/traditional/edit-requests?status=PENDING", t)
        check(pendingOnly.size == 1 && pendingOnly.single().id == teacherRequest.id.toString())
        mockMvc.perform(
            get("/traditional/edit-requests?status=NOPE").header("Authorization", "Bearer " + t)
        ).andExpect(status().isBadRequest)
    }

    @Test
    fun `an unassigned coordinator sees nothing rather than the school-wide queue`() {
        check(requests("/traditional/edit-requests", token(unassigned)).isEmpty())
        val body = mockMvc.perform(
            get("/traditional/edit-requests/summary").header("Authorization", "Bearer " + token(unassigned))
        ).andExpect(status().isOk).andReturn().response.contentAsString
        val summary = objectMapper.readValue(body, TraditionalCoordinatorSummaryDto::class.java)
        check(summary.pendingEditRequests == 0 && summary.pendingExamsForFinalization == 0 && summary.totalExams == 0) {
            "unassigned coordinator summary leaked: " + summary
        }
    }

    @Test
    fun `a plain teacher sees only their own requests and the admin sees the school`() {
        val teacherRows = requests("/traditional/edit-requests", token(teacher))
        check(teacherRows.size == 1 && teacherRows.single().id == teacherRequest.id.toString())

        val adminRows = requests("/traditional/edit-requests", token(admin))
        check(adminRows.size == 3) { "admin saw " + adminRows.size }
    }

    @Test
    fun `coordinator summary counts only the scoped grade`() {
        val body = mockMvc.perform(
            get("/traditional/edit-requests/summary").header("Authorization", "Bearer " + token(coordinator))
        ).andExpect(status().isOk).andReturn().response.contentAsString
        val summary = objectMapper.readValue(body, TraditionalCoordinatorSummaryDto::class.java)
        check(summary.pendingEditRequests == 2) { "pending=" + summary.pendingEditRequests }
        check(summary.pendingExamsForFinalization == 1) { "pendingExams=" + summary.pendingExamsForFinalization }
        check(summary.totalExams == 1) { "totalExams=" + summary.totalExams }
    }

    @Test
    fun `the grade table returns ranked rows for a coordinators exam`() {
        val alice = user("Alice Learner", "0788800005", school.id)
        val bob = user("Bob Learner", "0788800006", school.id)
        subjectRepository.save(TraditionalExamSubjectEntity().apply {
            examId = examGrade7.id
            subjectId = "math"
            name = "Mathematics"
            maxScore = 100
            subjectType = TraditionalSubjectType.SINGLE
        })
        markRepository.save(TraditionalMarkEntity().apply {
            examId = examGrade7.id
            studentId = alice.id
            subjectId = "math"
            rawScore = 88
            percentage = 88.0
            confirmedByTeacher = true
        })
        markRepository.save(TraditionalMarkEntity().apply {
            examId = examGrade7.id
            studentId = bob.id
            subjectId = "math"
            rawScore = 42
            percentage = 42.0
            confirmedByTeacher = true
        })
        val body = mockMvc.perform(
            get("/traditional/exams/" + examGrade7.id + "/grade-table")
                .header("Authorization", "Bearer " + token(coordinator))
        ).andExpect(status().isOk).andReturn().response.contentAsString
        val rows = objectMapper.readValue(body, Array<StudentGradeRowDto>::class.java).toList()
        check(rows.size == 2) { "rows=" + rows.size }
        check(rows.first().studentName == "Alice Learner" && rows.first().rank == 1)
        check(rows.last().studentName == "Bob Learner" && rows.last().rank == 2)
        check(rows.first().totalScore == 88 && rows.first().totalPercentage == 88.0)
        check(rows.first().overallGrade.isNotBlank())
    }
}
