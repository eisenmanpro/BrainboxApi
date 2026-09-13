package com.afrithecus.brainbox.api.teacher

import com.afrithecus.brainbox.api.auth.web.AuthResponse
import com.afrithecus.brainbox.api.classes.entity.ClassMembershipEntity
import com.afrithecus.brainbox.api.classes.entity.TeacherClassEntity
import com.afrithecus.brainbox.api.classes.repository.ClassMembershipRepository
import com.afrithecus.brainbox.api.classes.repository.TeacherClassRepository
import com.afrithecus.brainbox.api.classes.web.StudentInClassPayload
import com.afrithecus.brainbox.api.conference.entity.ConferenceBookingEntity
import com.afrithecus.brainbox.api.conference.entity.ConferenceSlotEntity
import com.afrithecus.brainbox.api.conference.repository.ConferenceBookingRepository
import com.afrithecus.brainbox.api.conference.repository.ConferenceSlotRepository
import com.afrithecus.brainbox.api.homework.entity.HomeworkEntity
import com.afrithecus.brainbox.api.homework.entity.HomeworkSubmissionEntity
import com.afrithecus.brainbox.api.homework.model.GradingMode
import com.afrithecus.brainbox.api.homework.model.SubmissionStatus
import com.afrithecus.brainbox.api.homework.model.SubmissionType
import com.afrithecus.brainbox.api.homework.repository.HomeworkRepository
import com.afrithecus.brainbox.api.homework.repository.HomeworkSubmissionRepository
import com.afrithecus.brainbox.api.identity.entity.SchoolEntity
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.model.AccountStatus
import com.afrithecus.brainbox.api.identity.model.Role
import com.afrithecus.brainbox.api.identity.model.SubRole
import com.afrithecus.brainbox.api.identity.repository.SchoolRepository
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import com.afrithecus.brainbox.api.teacher.web.TeacherDashboardPayload
import com.afrithecus.brainbox.api.traditional.entity.TraditionalEditRequestEntity
import com.afrithecus.brainbox.api.traditional.entity.TraditionalExamEntity
import com.afrithecus.brainbox.api.traditional.model.ExamTerm
import com.afrithecus.brainbox.api.traditional.model.TraditionalEditStatus
import com.afrithecus.brainbox.api.traditional.model.TraditionalExamStatus
import com.afrithecus.brainbox.api.traditional.repository.TraditionalEditRequestRepository
import com.afrithecus.brainbox.api.traditional.repository.TraditionalExamRepository
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
import java.util.UUID

/** GET teacher/dashboard counts + enriched roster (api_teacher_roster_changes.md). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class TeacherDashboardWebTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val userRepository: UserRepository,
    @Autowired private val schoolRepository: SchoolRepository,
    @Autowired private val classRepository: TeacherClassRepository,
    @Autowired private val membershipRepository: ClassMembershipRepository,
    @Autowired private val homeworkRepository: HomeworkRepository,
    @Autowired private val submissionRepository: HomeworkSubmissionRepository,
    @Autowired private val examRepository: TraditionalExamRepository,
    @Autowired private val editRequestRepository: TraditionalEditRequestRepository,
    @Autowired private val slotRepository: ConferenceSlotRepository,
    @Autowired private val bookingRepository: ConferenceBookingRepository,
    @Autowired private val passwordEncoder: PasswordEncoder,
) {

    private lateinit var school: SchoolEntity
    private lateinit var teacher: UserEntity
    private lateinit var clazz: TeacherClassEntity

    private fun user(role: Role, name: String, phone: String, subRole: SubRole? = null, grade: String? = null): UserEntity {
        val entity = UserEntity()
        entity.phoneNumber = phone
        entity.email = phone + "@dashboard.test"
        entity.passwordHash = passwordEncoder.encode("password123") ?: error("encode")
        entity.name = name
        entity.role = role
        entity.subRole = subRole
        entity.schoolId = school.id
        entity.gradeLevel = grade
        entity.isActive = true
        entity.isVerified = true
        return userRepository.save(entity)
    }

    private fun token(entity: UserEntity): String {
        val response = mockMvc.perform(
            post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"identifier\":\"" + entity.phoneNumber + "\",\"password\":\"password123\"}")
        ).andReturn().response
        check(response.status == 200) { "login failed: " + response.status + " " + response.contentAsString }
        return objectMapper.readValue(response.contentAsString, AuthResponse::class.java).sessionToken!!
    }

    @BeforeEach
    fun setUp() {
        school = schoolRepository.save(SchoolEntity().apply { name = "Alliance High School"; isActive = true })
        teacher = user(Role.TEACHER, "Coordinator", "0755300001", SubRole.GRADE_COORDINATOR, "Grade 4")
        clazz = classRepository.save(TeacherClassEntity().apply {
            teacherUserId = teacher.id
            schoolId = school.id
            name = "Grade 4 East"
            gradeLevel = "Grade 4"
            subject = "Mathematics"
            isActive = true
        })
    }

    @Test
    fun `dashboard reports real work-queue counts and roster is enriched`() {
        val parent = user(Role.PARENT, "Parent One", "0755300002")
        val student = user(Role.STUDENT, "Alice Learner", "0755300003", grade = "Grade 4")
        student.studentAdmissionNumber = "ADM-001"
        student.parentUserId = parent.id
        student.joinedTeacherId = teacher.id
        student.verificationStatus = AccountStatus.PENDING_VERIFICATION
        student.isVerified = false
        student.lastLogin = Instant.now()
        userRepository.save(student)
        membershipRepository.save(ClassMembershipEntity().apply { classId = clazz.id; studentId = student.id })

        val homework = homeworkRepository.save(HomeworkEntity().apply {
            id = "hw_1"
            classId = clazz.id
            teacherId = teacher.id
            teacherName = teacher.name
            schoolId = school.id
            title = "Algebra worksheet"
            description = "Complete exercise 4"
            subject = "Mathematics"
            gradeLevel = 4
            dueDate = Instant.now().plusSeconds(86_400)
            submissionType = SubmissionType.FREE_TEXT
            gradingMode = GradingMode.MANUAL
            isActive = true
        })
        submissionRepository.saveAndFlush(HomeworkSubmissionEntity().apply {
            homeworkId = homework.id
            studentId = student.id
            status = SubmissionStatus.PENDING
            submittedAt = Instant.now()
        })

        val exam = examRepository.saveAndFlush(TraditionalExamEntity().apply {
            title = "End Term 2 2026"
            term = ExamTerm.TERM_2
            gradeLevel = "Grade 4"
            year = 2026
            status = TraditionalExamStatus.CONFIRMED
            schoolId = school.id
            createdBy = teacher.id
        })
        editRequestRepository.saveAndFlush(TraditionalEditRequestEntity().apply {
            examId = exam.id
            requesterId = teacher.id
            studentId = student.id
            subjectId = "MATH"
            oldScore = 60
            newScore = 70
            reason = "typo"
            status = TraditionalEditStatus.PENDING
        })

        val slot = slotRepository.saveAndFlush(ConferenceSlotEntity().apply {
            teacherId = teacher.id
            teacherName = teacher.name
            title = "Parent Evening"
            slotDate = Instant.now().plusSeconds(3_600)
            startTime = "10:00"
            endTime = "10:30"
        })
        bookingRepository.saveAndFlush(ConferenceBookingEntity().apply {
            slotId = slot.id
            parentId = parent.id
            childId = student.id
            status = "PENDING"
        })

        val t = token(teacher)
        val dashboard = objectMapper.readValue(
            mockMvc.perform(get("/teacher/dashboard").header("Authorization", "Bearer " + t))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            TeacherDashboardPayload::class.java,
        )
        check(dashboard.classes.size == 1)
        check(dashboard.classes.single().name == "Grade 4 East")
        check(dashboard.pendingGradingCount == 1) { "pendingGrading=" + dashboard.pendingGradingCount }
        check(dashboard.pendingApprovalsCount == 1) { "pendingApprovals=" + dashboard.pendingApprovalsCount }
        check(dashboard.conferenceRequestsCount == 1) { "conferences=" + dashboard.conferenceRequestsCount }
        check(dashboard.pendingFinalizationsCount == 1) { "finalizations=" + dashboard.pendingFinalizationsCount }
        check(dashboard.pendingEditRequestsCount == 1) { "editRequests=" + dashboard.pendingEditRequestsCount }
        check(dashboard.upcomingDeadlines.any { it.id == "hw_1" })
        check(dashboard.urgentQueue.any { it.studentId == student.id.toString() && it.status == "SUBMITTED" })

        val roster = objectMapper.readValue(
            mockMvc.perform(get("/teacher/classes/" + clazz.id + "/students").header("Authorization", "Bearer " + t))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            Array<StudentInClassPayload>::class.java,
        )
        val row = roster.single()
        check(row.id == student.id.toString())
        check(row.admissionNumber == "ADM-001")
        check(row.grade == 4)
        check(row.parentId == parent.id.toString())
        check(row.lastActive > 0)
    }

    @Test
    fun `dashboard is staff-only`() {
        val student = user(Role.STUDENT, "Learner", "0755300004", grade = "Grade 4")
        mockMvc.perform(get("/teacher/dashboard").header("Authorization", "Bearer " + token(student)))
            .andExpect(status().isForbidden)
    }
}
