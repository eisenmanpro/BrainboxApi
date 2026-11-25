package com.afrithecus.brainbox.api.traditional

import com.afrithecus.brainbox.api.auth.web.AuthResponse
import com.afrithecus.brainbox.api.classes.entity.ClassMembershipEntity
import com.afrithecus.brainbox.api.classes.entity.TeacherClassEntity
import com.afrithecus.brainbox.api.classes.repository.ClassMembershipRepository
import com.afrithecus.brainbox.api.classes.repository.TeacherClassRepository
import com.afrithecus.brainbox.api.identity.entity.SchoolEntity
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.model.CurrentUser
import com.afrithecus.brainbox.api.identity.model.Role
import com.afrithecus.brainbox.api.identity.model.SubRole
import com.afrithecus.brainbox.api.identity.repository.SchoolRepository
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import com.afrithecus.brainbox.api.notification.web.AppNotificationPayload
import com.afrithecus.brainbox.api.traditional.repository.TraditionalExamRepository
import com.afrithecus.brainbox.api.traditional.model.ExamTerm
import com.afrithecus.brainbox.api.traditional.model.TraditionalEditStatus
import com.afrithecus.brainbox.api.traditional.model.TraditionalExamStatus
import com.afrithecus.brainbox.api.traditional.model.TraditionalOmitPolicy
import com.afrithecus.brainbox.api.traditional.model.TraditionalSubjectType
import com.afrithecus.brainbox.api.traditional.web.CaptureStudentStatus
import com.afrithecus.brainbox.api.traditional.web.CaptureSummaryDto
import com.afrithecus.brainbox.api.traditional.web.CreateTraditionalExamRequest
import com.afrithecus.brainbox.api.traditional.web.EditRequestDto
import com.afrithecus.brainbox.api.traditional.web.ExamConfirmationStatusDto
import com.afrithecus.brainbox.api.traditional.web.GradingBandDto
import com.afrithecus.brainbox.api.traditional.web.GradingConfigDto
import com.afrithecus.brainbox.api.traditional.web.MarkEntryDto
import com.afrithecus.brainbox.api.traditional.web.PreFinalCheckSummaryDto
import com.afrithecus.brainbox.api.traditional.web.StudentDto
import com.afrithecus.brainbox.api.traditional.web.StudentGradeRowDto
import com.afrithecus.brainbox.api.traditional.web.SubjectComponentDto
import com.afrithecus.brainbox.api.traditional.web.SubjectConfigDto
import com.afrithecus.brainbox.api.traditional.web.TraditionalExamAnalyticsDto
import com.afrithecus.brainbox.api.traditional.web.TraditionalClassDto
import com.afrithecus.brainbox.api.traditional.web.TraditionalExamDto
import com.afrithecus.brainbox.api.traditional.web.TraditionalGradeAnalysisDto
import com.afrithecus.brainbox.api.traditional.web.TraditionalMarkDto
import com.afrithecus.brainbox.api.traditional.web.TraditionalStudentReportDto
import com.afrithecus.brainbox.api.traditional.web.UpdateTraditionalExamRequest
import com.afrithecus.brainbox.api.traditional.web.ValidateAccessResponse
import com.afrithecus.brainbox.api.report.ReportStorage
import com.afrithecus.brainbox.api.report.repository.ReportJobRepository
import com.afrithecus.brainbox.api.report.web.ReportJobPayload
import com.afrithecus.brainbox.api.report.web.ReportJobStatus
import com.afrithecus.brainbox.api.report.web.ReportType
import com.afrithecus.brainbox.api.report.web.StudentReportRequestPayload
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
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.util.UUID
import kotlin.math.abs

/**
 * Traditional exam lifecycle end to end (docs 10/13 + api_student_reports_changes.md):
 * coordinator captures and publishes marks, then the student and linked parent read
 * the server-computed report. Also covers the publication gate, ownership and the
 * student/parent notification fan-out.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class TraditionalExamWebTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val userRepository: UserRepository,
    @Autowired private val schoolRepository: SchoolRepository,
    @Autowired private val passwordEncoder: PasswordEncoder,
    @Autowired private val teacherClassRepository: TeacherClassRepository,
    @Autowired private val classMembershipRepository: ClassMembershipRepository,
    @Autowired private val examRepository: TraditionalExamRepository,
    @Autowired private val service: TraditionalExamService,
    @Autowired private val reportJobs: ReportJobRepository,
    @Autowired private val reportStorage: ReportStorage,
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

    private fun user(
        role: Role,
        name: String,
        phone: String,
        grade: String? = null,
        subRole: SubRole? = null,
        parentId: UUID? = null,
        admission: String? = null,
    ): UserEntity {
        val entity = UserEntity()
        entity.phoneNumber = phone
        entity.email = phone + "@traditional.test"
        entity.passwordHash = passwordEncoder.encode("password123") ?: error("encode")
        entity.name = name
        entity.role = role
        entity.subRole = subRole
        entity.schoolId = schoolId
        entity.gradeLevel = grade
        entity.studentAdmissionNumber = admission
        entity.parentUserId = parentId
        entity.isVerified = true
        entity.isActive = true
        return userRepository.save(entity)
    }

    private fun token(entity: UserEntity): String {
        val login = mockMvc.perform(
            post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"identifier\":\"${entity.email}\",\"password\":\"password123\"}")
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return objectMapper.readValue(login, AuthResponse::class.java).sessionToken!!
    }

    private fun auth(token: String) = "Bearer " + token

    /** A fresh (no client id) edit request; the server adopts the caller as the requester. */
    private fun editRequest(examId: String, studentId: UUID, subjectId: String, oldScore: Int, newScore: Int): EditRequestDto =
        EditRequestDto(
            id = "",
            examId = examId,
            teacherId = "",
            studentId = studentId.toString(),
            subjectId = subjectId,
            oldScore = oldScore,
            newScore = newScore,
            reason = "capture typo",
            createdAt = 0,
        )

    private fun createExam(
        token: String,
        gradeLevel: String,
        subjects: List<SubjectConfigDto>,
        year: Int = 2026,
        grading: GradingConfigDto? = null,
    ): TraditionalExamDto {
        val body = objectMapper.writeValueAsString(
            CreateTraditionalExamRequest(
                title = "End Term 2 " + year + " - " + gradeLevel,
                term = ExamTerm.TERM_2,
                gradeLevel = gradeLevel,
                year = year,
                subjects = subjects,
                grading = grading,
            )
        )
        val response = mockMvc.perform(
            post("/traditional/exams").header("Authorization", auth(token))
                .contentType(MediaType.APPLICATION_JSON).content(body)
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return objectMapper.readValue(response, TraditionalExamDto::class.java)
    }

    private fun publishExam(examId: String, token: String, coordinatorId: UUID) {
        // Replaying each lifecycle step must be safe (the client outbox retries them).
        repeat(2) {
            mockMvc.perform(
                post("/traditional/exams/${examId}/confirm").param("grade", "Grade 4").header("Authorization", auth(token))
            ).andExpect(status().isNoContent)
            mockMvc.perform(
                post("/traditional/exams/${examId}/pre-final").header("Authorization", auth(token))
            ).andExpect(status().isOk)
            mockMvc.perform(
                post("/traditional/exams/${examId}/finalize").param("coordinatorId", coordinatorId.toString())
                    .param("remarks", "Great term").header("Authorization", auth(token))
            ).andExpect(status().isOk)
            mockMvc.perform(
                post("/traditional/exams/${examId}/publish").param("coordinatorId", coordinatorId.toString())
                    .header("Authorization", auth(token))
            ).andExpect(status().isOk)
        }
    }

    @Test
    fun `publication pipeline gives students and linked parents the computed report`() {
        val parent = user(Role.PARENT, "Parent One", "0700000100")
        val alice = user(Role.STUDENT, "Alice Mwangi", "0700000101", grade = "Grade 4", parentId = parent.id, admission = "ADM-001")
        val bob = user(Role.STUDENT, "Bob Otieno", "0700000102", grade = "Grade 4", admission = "ADM-002")
        val coordinator = user(Role.TEACHER, "Coordinator", "0700000103", grade = "Grade 4", subRole = SubRole.GRADE_COORDINATOR)

        val coordinatorToken = token(coordinator)
        val aliceToken = token(alice)
        val parentToken = token(parent)

        val exam = createExam(
            coordinatorToken, "Grade 4",
            listOf(
                SubjectConfigDto("MATH", "Mathematics", 100, TraditionalSubjectType.SINGLE),
                SubjectConfigDto("ENG", "English", 100, TraditionalSubjectType.SINGLE),
            ),
        )
        check(exam.status == TraditionalExamStatus.PENDING)
        check(exam.subjects.size == 2)

        val entries = listOf(
            MarkEntryDto(alice.id.toString(), "MATH", 80),
            MarkEntryDto(alice.id.toString(), "ENG", 60),
            MarkEntryDto(bob.id.toString(), "MATH", 70),
            MarkEntryDto(bob.id.toString(), "ENG", 90),
        )
        val marksBody = objectMapper.writeValueAsString(entries)
        mockMvc.perform(
            post("/traditional/exams/${exam.examId}/marks").header("Authorization", auth(coordinatorToken))
                .contentType(MediaType.APPLICATION_JSON).content(marksBody)
        ).andExpect(status().isOk)
        // Offline replay is idempotent: same rows, no duplicates.
        mockMvc.perform(
            post("/traditional/exams/${exam.examId}/marks").header("Authorization", auth(coordinatorToken))
                .contentType(MediaType.APPLICATION_JSON).content(marksBody)
        ).andExpect(status().isOk)

        val marks = objectMapper.readValue(
            mockMvc.perform(get("/traditional/exams/${exam.examId}/marks").header("Authorization", auth(coordinatorToken)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            Array<TraditionalMarkDto>::class.java,
        )
        check(marks.size == 4)
        check(marks.first { it.studentId == alice.id.toString() && it.subjectId == "MATH" }.gradeBand == "EE")

        // Marks are teacher-only; a student is forbidden.
        mockMvc.perform(get("/traditional/exams/${exam.examId}/marks").header("Authorization", auth(aliceToken)))
            .andExpect(status().isForbidden)

        // The publication gate blocks results before publish.
        mockMvc.perform(get("/traditional/exams/${exam.examId}/results/me").header("Authorization", auth(aliceToken)))
            .andExpect(status().isForbidden)

        val checks = objectMapper.readValue(
            mockMvc.perform(get("/traditional/exams/${exam.examId}/pre-final-checks").header("Authorization", auth(coordinatorToken)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            PreFinalCheckSummaryDto::class.java,
        )
        check(checks.gradeChecks.single().completeStudents == 2)

        publishExam(exam.examId, coordinatorToken, coordinator.id)

        // Student listing now includes the published exam.
        val listed = objectMapper.readValue(
            mockMvc.perform(get("/traditional/exams").param("grade", "Grade 4").header("Authorization", auth(aliceToken)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            Array<TraditionalExamDto>::class.java,
        )
        check(listed.any { it.examId == exam.examId && it.status == TraditionalExamStatus.PUBLISHED && it.publishedAt != null })

        // Server-computed per-caller report.
        val report = objectMapper.readValue(
            mockMvc.perform(get("/traditional/exams/${exam.examId}/results/me").header("Authorization", auth(aliceToken)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            TraditionalStudentReportDto::class.java,
        )
        check(report.studentId == alice.id.toString())
        check(report.studentName == "Alice Mwangi")
        check(report.admissionNumber == "ADM-001")
        check(report.gradeLevel == "Grade 4")
        check(report.term == "Term 2")
        check(report.subjectResults.size == 2)
        check(report.totalScore == 140)
        check(report.overallPercentage == 70.0)
        // D17/G1.4: the overall level is the band of the MEAN percentage (70 % -> ME under the
        // default 80/65/50/35/20/0 bands). The old raw-total thresholds called 140 a "BE"
        // only because they were sized for a five-subject total; that rule is gone.
        check(report.overallGrade == "ME")
        check(report.classPosition == 2)
        check(report.totalStudentsInClass == 2)

        // A linked parent reads the child's report; an unrelated request is forbidden.
        mockMvc.perform(
            get("/traditional/exams/${exam.examId}/results/me").param("studentId", alice.id.toString())
                .header("Authorization", auth(parentToken))
        ).andExpect(status().isOk)
        mockMvc.perform(
            get("/traditional/exams/${exam.examId}/results/me").param("studentId", bob.id.toString())
                .header("Authorization", auth(parentToken))
        ).andExpect(status().isForbidden)

        // Publish fans out notifications with the deep-link routes the client handles.
        val studentNotifications = objectMapper.readValue(
            mockMvc.perform(get("/notifications").param("userId", alice.id.toString()).header("Authorization", auth(aliceToken)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            Array<AppNotificationPayload>::class.java,
        )
        check(studentNotifications.any { it.actionRoute == "exam_results/" + exam.examId })
        val parentNotifications = objectMapper.readValue(
            mockMvc.perform(get("/notifications").param("userId", parent.id.toString()).header("Authorization", auth(parentToken)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            Array<AppNotificationPayload>::class.java,
        )
        check(parentNotifications.any { it.actionRoute == "student_report/" + alice.id.toString() })

        // Coordinator analytics and rankings.
        val analytics = objectMapper.readValue(
            mockMvc.perform(get("/traditional/exams/${exam.examId}/analytics").header("Authorization", auth(coordinatorToken)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            TraditionalExamAnalyticsDto::class.java,
        )
        check(analytics.totalStudents == 2)
        check(analytics.topPerformers.first().studentId == bob.id.toString())
        check(analytics.subjectAverages.keys.containsAll(setOf("MATH", "ENG")))

        val ranking = objectMapper.readValue(
            mockMvc.perform(get("/traditional/exams/${exam.examId}/grade-wide-ranking").header("Authorization", auth(coordinatorToken)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            Array<StudentGradeRowDto>::class.java,
        )
        check(ranking.size == 2)
        check(ranking.first().studentId == bob.id.toString())
        check(ranking.first().rank == 1)
    }

    @Test
    fun `combined subjects require component scores that sum to the raw mark`() {
        val coordinator = user(Role.TEACHER, "Coordinator Two", "0700000200", grade = "Grade 5", subRole = SubRole.GRADE_COORDINATOR)
        val student = user(Role.STUDENT, "Carol Wanjiru", "0700000201", grade = "Grade 5", admission = "ADM-101")
        val token = token(coordinator)

        val exam = createExam(
            token, "Grade 5",
            listOf(
                SubjectConfigDto(
                    "SCI", "Science & Technology", 100, TraditionalSubjectType.COMBINED,
                    components = listOf(
                        SubjectComponentDto("S/TEC", "S/TEC", 50),
                        SubjectComponentDto("AGR/NUT", "AGR/NUT", 50),
                    ),
                )
            ),
        )

        val bad = objectMapper.writeValueAsString(
            listOf(MarkEntryDto(student.id.toString(), "SCI", 90, mapOf("S/TEC" to 40, "AGR/NUT" to 40)))
        )
        mockMvc.perform(
            post("/traditional/exams/${exam.examId}/marks").header("Authorization", auth(token))
                .contentType(MediaType.APPLICATION_JSON).content(bad)
        ).andExpect(status().isBadRequest)

        val good = objectMapper.writeValueAsString(
            listOf(MarkEntryDto(student.id.toString(), "SCI", 90, mapOf("S/TEC" to 40, "AGR/NUT" to 50)))
        )
        val saved = objectMapper.readValue(
            mockMvc.perform(
                post("/traditional/exams/${exam.examId}/marks").header("Authorization", auth(token))
                    .contentType(MediaType.APPLICATION_JSON).content(good)
            ).andExpect(status().isOk).andReturn().response.contentAsString,
            Array<TraditionalMarkDto>::class.java,
        )
        check(saved.single().rawScore == 90)
        check(saved.single().percentage == 90.0)
        check(saved.single().componentScores?.get("S/TEC") == 40)
    }

    @Test
    fun `grade config round-trips, generation is idempotent and self-approval unlocks the exam`() {
        val coordinator = user(Role.TEACHER, "Coordinator Three", "0700000300", grade = "Grade 6", subRole = SubRole.GRADE_COORDINATOR)
        val student = user(Role.STUDENT, "Dave Kamau", "0700000302", grade = "Grade 6")
        val token = token(coordinator)

        val subjects = listOf(
            SubjectConfigDto("MAT", "Mathematics", 100, TraditionalSubjectType.SINGLE),
            SubjectConfigDto("KIS", "Kiswahili", 100, TraditionalSubjectType.SINGLE),
        )
        mockMvc.perform(
            post("/traditional/grades/Grade 6/subjects").param("schoolId", schoolId.toString())
                .header("Authorization", auth(token)).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(subjects))
        ).andExpect(status().isNoContent)
        val savedSubjects = objectMapper.readValue(
            mockMvc.perform(
                get("/traditional/grades/Grade 6/subjects").param("schoolId", schoolId.toString())
                    .header("Authorization", auth(token))
            ).andExpect(status().isOk).andReturn().response.contentAsString,
            Array<SubjectConfigDto>::class.java,
        )
        check(savedSubjects.size == 2)

        val defaultGrading = objectMapper.readValue(
            mockMvc.perform(
                get("/traditional/grades/Grade 6/grading").param("schoolId", schoolId.toString())
                    .header("Authorization", auth(token))
            ).andExpect(status().isOk).andReturn().response.contentAsString,
            GradingConfigDto::class.java,
        )
        check(defaultGrading.bands.any { it.grade == "EE" && it.minPercentage == 80 })
        mockMvc.perform(
            post("/traditional/grades/Grade 6/grading").param("schoolId", schoolId.toString())
                .header("Authorization", auth(token)).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(GradingConfigDto(bands = listOf(GradingBandDto("EE", 75), GradingBandDto("E", 0)))))
        ).andExpect(status().isNoContent)

        val generateBody = objectMapper.writeValueAsString(subjects)
        val generated = objectMapper.readValue(
            mockMvc.perform(
                post("/traditional/exams/generate").param("grade", "Grade 6").param("term", "TERM_1").param("year", "2026")
                    .header("Authorization", auth(token)).contentType(MediaType.APPLICATION_JSON).content(generateBody)
            ).andExpect(status().isOk).andReturn().response.contentAsString,
            Array<TraditionalExamDto>::class.java,
        )
        // The client generates three deterministic sessions locally; the server mirrors
        // exactly those ids so marks and confirmations can follow.
        check(generated.size == 3)
        check(
            generated.map { it.examId }.toSet() == setOf(
                "TRAD_Grade6_OPENER_2026_T1",
                "TRAD_Grade6_MID_2026_T1",
                "TRAD_Grade6_END_2026_T1",
            )
        )
        val regenerated = objectMapper.readValue(
            mockMvc.perform(
                post("/traditional/exams/generate").param("grade", "Grade 6").param("term", "TERM_1").param("year", "2026")
                    .header("Authorization", auth(token)).contentType(MediaType.APPLICATION_JSON).content(generateBody)
            ).andExpect(status().isOk).andReturn().response.contentAsString,
            Array<TraditionalExamDto>::class.java,
        )
        check(regenerated.size == 3 && regenerated.map { it.examId }.toSet() == generated.map { it.examId }.toSet())

        val examId = generated[0].examId
        // Edit requests are only valid once the exam is finalized.
        mockMvc.perform(
            post("/traditional/exams/${examId}/marks").header("Authorization", auth(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(listOf(MarkEntryDto(student.id.toString(), "MAT", 50))))
        ).andExpect(status().isOk)
        mockMvc.perform(
            post("/traditional/exams/${examId}/confirm").param("grade", "Grade 6").header("Authorization", auth(token))
        ).andExpect(status().isNoContent)
        mockMvc.perform(
            post("/traditional/exams/${examId}/pre-final").header("Authorization", auth(token))
        ).andExpect(status().isOk)
        mockMvc.perform(
            post("/traditional/exams/${examId}/finalize").header("Authorization", auth(token))
        ).andExpect(status().isOk)
        val request = EditRequestDto(
            id = "", examId = examId, teacherId = coordinator.id.toString(), studentId = student.id.toString(),
            subjectId = "MAT", oldScore = 50, newScore = 60, reason = "capture typo", createdAt = 0,
        )
        val created = objectMapper.readValue(
            mockMvc.perform(
                post("/traditional/exams/${examId}/edit-requests").header("Authorization", auth(token))
                    .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(request))
            ).andExpect(status().isOk).andReturn().response.contentAsString,
            EditRequestDto::class.java,
        )
        check(created.status == TraditionalEditStatus.PENDING)
        // F7: a coordinator may approve their own request; the decision unlocks the
        // submitted exam for correction and the teacher re-submits. The responsibility
        // warning is a client affordance, not a server refusal.
        val approved = objectMapper.readValue(
            mockMvc.perform(
                post("/traditional/edit-requests/${created.id}/approve").header("Authorization", auth(token))
            ).andExpect(status().isOk).andReturn().response.contentAsString,
            EditRequestDto::class.java,
        )
        check(approved.status == TraditionalEditStatus.APPROVED)
        val reopened = objectMapper.readValue(
            mockMvc.perform(get("/traditional/exams/${examId}").header("Authorization", auth(token)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            TraditionalExamDto::class.java,
        )
        check(reopened.status == TraditionalExamStatus.IN_PROGRESS)
    }

    @Test
    fun `client assigned exam ids round-trip through marks confirmation and analytics`() {
        val coordinator = user(Role.TEACHER, "Coordinator Client", "0700000400", grade = "Grade 7", subRole = SubRole.GRADE_COORDINATOR)
        val teacherA = user(Role.TEACHER, "Teacher A", "0700000401", grade = "Grade 7")
        val teacherB = user(Role.TEACHER, "Teacher B", "0700000402", grade = "Grade 7")
        val alice = user(Role.STUDENT, "Alice Client", "0700000403", grade = "Grade 7", admission = "ADM-201")
        val bob = user(Role.STUDENT, "Bob Client", "0700000404", grade = "Grade 7", admission = "ADM-202")
        val coordinatorToken = token(coordinator)
        val tokenA = token(teacherA)
        val tokenB = token(teacherB)

        val clientExamId = "TRAD_Grade7_OPENER_2026_T2"
        val request = CreateTraditionalExamRequest(
            examId = clientExamId,
            title = "Opener Term 2 2026 - Grade 7",
            term = ExamTerm.TERM_2,
            gradeLevel = "Grade 7",
            year = 2026,
            subjects = listOf(SubjectConfigDto("MATH", "Mathematics", 100, TraditionalSubjectType.SINGLE)),
        )
        val created = objectMapper.readValue(
            mockMvc.perform(
                post("/traditional/exams").header("Authorization", auth(coordinatorToken))
                    .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(request))
            ).andExpect(status().isOk).andReturn().response.contentAsString,
            TraditionalExamDto::class.java,
        )
        check(created.examId == clientExamId)
        check(created.schoolId == schoolId.toString())
        // Replaying the same create is idempotent in the client id (client outbox).
        val replayed = objectMapper.readValue(
            mockMvc.perform(
                post("/traditional/exams").header("Authorization", auth(coordinatorToken))
                    .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(request))
            ).andExpect(status().isOk).andReturn().response.contentAsString,
            TraditionalExamDto::class.java,
        )
        check(replayed.examId == clientExamId)

        val saved = objectMapper.readValue(
            mockMvc.perform(
                post("/traditional/exams/${clientExamId}/marks").header("Authorization", auth(tokenA))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(listOf(MarkEntryDto(alice.id.toString(), "MATH", 80))))
            ).andExpect(status().isOk).andReturn().response.contentAsString,
            Array<TraditionalMarkDto>::class.java,
        )
        check(saved.single().examId == clientExamId)
        // The mark id mirrors the client's deterministic key so a save response
        // replaces, rather than duplicates, the local row.
        check(saved.single().id == clientExamId + "_" + alice.id + "_MATH")
        mockMvc.perform(
            post("/traditional/exams/${clientExamId}/marks").header("Authorization", auth(tokenB))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(listOf(MarkEntryDto(bob.id.toString(), "MATH", 40))))
        ).andExpect(status().isOk)

        mockMvc.perform(
            post("/traditional/exams/${clientExamId}/confirm").param("grade", "Grade 7").header("Authorization", auth(tokenA))
        ).andExpect(status().isNoContent)
        val status = objectMapper.readValue(
            mockMvc.perform(get("/traditional/exams/${clientExamId}/confirmation-status").header("Authorization", auth(coordinatorToken)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            ExamConfirmationStatusDto::class.java,
        )
        check(status.examId == clientExamId)
        check(status.totalTeachers == 2 && status.confirmedCount == 1)
        check(status.pendingTeachers == listOf(teacherB.id.toString()))

        mockMvc.perform(
            post("/traditional/exams/${clientExamId}/confirm").param("grade", "Grade 7").header("Authorization", auth(tokenB))
        ).andExpect(status().isNoContent)
        val analytics = objectMapper.readValue(
            mockMvc.perform(get("/traditional/exams/${clientExamId}/analytics").header("Authorization", auth(coordinatorToken)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            TraditionalExamAnalyticsDto::class.java,
        )
        check(analytics.examId == clientExamId)
        check(analytics.totalTeachers == 2 && analytics.confirmedTeachers == 2)
        check(analytics.totalStudents == 2)
        // Summed subject percentages (Alice 80, Bob 40) -> mean 60, matching the client.
        check(analytics.classMean == 60.0)
    }

    @Test
    fun `client supplied edit request id is adopted for later approval`() {
        val coordinator = user(Role.TEACHER, "Coordinator Edit", "0700000500", grade = "Grade 8", subRole = SubRole.GRADE_COORDINATOR)
        val teacher = user(Role.TEACHER, "Teacher Edit", "0700000501", grade = "Grade 8", subRole = SubRole.CTEACHER)
        val student = user(Role.STUDENT, "Student Edit", "0700000502", grade = "Grade 8")
        val coordinatorToken = token(coordinator)
        val teacherToken = token(teacher)

        val exam = createExam(coordinatorToken, "Grade 8", listOf(SubjectConfigDto("MAT", "Mathematics", 100, TraditionalSubjectType.SINGLE)))
        mockMvc.perform(
            post("/traditional/exams/${exam.examId}/marks").header("Authorization", auth(teacherToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(listOf(MarkEntryDto(student.id.toString(), "MAT", 50))))
        ).andExpect(status().isOk)
        // Edit requests are only valid on a finalized exam; lock the marks first.
        mockMvc.perform(
            post("/traditional/exams/${exam.examId}/confirm").param("grade", "Grade 8").header("Authorization", auth(teacherToken))
        ).andExpect(status().isNoContent)
        mockMvc.perform(
            post("/traditional/exams/${exam.examId}/pre-final").header("Authorization", auth(coordinatorToken))
        ).andExpect(status().isOk)
        mockMvc.perform(
            post("/traditional/exams/${exam.examId}/finalize").header("Authorization", auth(coordinatorToken))
        ).andExpect(status().isOk)

        val requestId = UUID.randomUUID().toString()
        val request = EditRequestDto(
            id = requestId, examId = exam.examId, teacherId = teacher.id.toString(), studentId = student.id.toString(),
            subjectId = "MAT", oldScore = 50, newScore = 60, reason = "capture typo", createdAt = 0,
        )
        val created = objectMapper.readValue(
            mockMvc.perform(
                post("/traditional/exams/${exam.examId}/edit-requests").header("Authorization", auth(teacherToken))
                    .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(request))
            ).andExpect(status().isOk).andReturn().response.contentAsString,
            EditRequestDto::class.java,
        )
        check(created.id == requestId)
        val approved = objectMapper.readValue(
            mockMvc.perform(
                post("/traditional/edit-requests/${requestId}/approve").header("Authorization", auth(coordinatorToken))
            ).andExpect(status().isOk).andReturn().response.contentAsString,
            EditRequestDto::class.java,
        )
        check(approved.status == TraditionalEditStatus.APPROVED)
    }

    @Test
    fun `published exam rejects further mark writes but replays confirm as a no-op`() {
        val coordinator = user(Role.TEACHER, "Coordinator Pub", "0700000600", grade = "Grade 4", subRole = SubRole.GRADE_COORDINATOR)
        val student = user(Role.STUDENT, "Student Pub", "0700000601", grade = "Grade 4", admission = "ADM-301")
        val token = token(coordinator)
        val exam = createExam(token, "Grade 4", listOf(SubjectConfigDto("MATH", "Mathematics", 100, TraditionalSubjectType.SINGLE)))
        mockMvc.perform(
            post("/traditional/exams/${exam.examId}/marks").header("Authorization", auth(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(listOf(MarkEntryDto(student.id.toString(), "MATH", 70))))
        ).andExpect(status().isOk)
        publishExam(exam.examId, token, coordinator.id)

        mockMvc.perform(
            post("/traditional/exams/${exam.examId}/marks").header("Authorization", auth(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(listOf(MarkEntryDto(student.id.toString(), "MATH", 90))))
        ).andExpect(status().isConflict)
        mockMvc.perform(
            post("/traditional/exams/${exam.examId}/confirm").param("grade", "Grade 4").header("Authorization", auth(token))
        ).andExpect(status().isNoContent)
    }

    @Test
    fun `lifecycle only advances one guarded step at a time`() {
        val coordinator = user(Role.TEACHER, "Coordinator Lifecycle", "0700000700", grade = "Grade 4", subRole = SubRole.GRADE_COORDINATOR)
        val token = token(coordinator)
        val exam = createExam(token, "Grade 4", listOf(SubjectConfigDto("MAT", "Mathematics", 100, TraditionalSubjectType.SINGLE)))

        // A single status write cannot skip the confirmation/finalization gates.
        mockMvc.perform(post("/traditional/exams/${exam.examId}/status/FINALIZED").header("Authorization", auth(token)))
            .andExpect(status().isConflict)
        mockMvc.perform(post("/traditional/exams/${exam.examId}/status/PUBLISHED").header("Authorization", auth(token)))
            .andExpect(status().isConflict)
        mockMvc.perform(post("/traditional/exams/${exam.examId}/status/CONFIRMED").header("Authorization", auth(token)))
            .andExpect(status().isConflict)
        mockMvc.perform(post("/traditional/exams/${exam.examId}/status/PENDING").header("Authorization", auth(token)))
            .andExpect(status().isConflict)

        // PENDING -> IN_PROGRESS is the one allowed forward step, and is replay-safe.
        mockMvc.perform(post("/traditional/exams/${exam.examId}/status/IN_PROGRESS").header("Authorization", auth(token)))
            .andExpect(status().isOk)
        mockMvc.perform(post("/traditional/exams/${exam.examId}/status/IN_PROGRESS").header("Authorization", auth(token)))
            .andExpect(status().isOk)
    }

    @Test
    fun `edit request guards require a finalized exam, a permitted sub-role and an in-range score`() {
        val coordinator = user(Role.TEACHER, "Coordinator Guards", "0700000800", grade = "Grade 5", subRole = SubRole.GRADE_COORDINATOR)
        val approver = user(Role.TEACHER, "Coordinator Approver", "0700000803", grade = "Grade 5", subRole = SubRole.GRADE_COORDINATOR)
        val plainTeacher = user(Role.TEACHER, "Plain Teacher", "0700000801", grade = "Grade 5")
        val student = user(Role.STUDENT, "Student Guards", "0700000802", grade = "Grade 5")
        val token = token(coordinator)

        val exam = createExam(token, "Grade 5", listOf(SubjectConfigDto("MAT", "Mathematics", 100, TraditionalSubjectType.SINGLE)))
        // Not finalized: the request is refused.
        mockMvc.perform(
            post("/traditional/exams/${exam.examId}/edit-requests").header("Authorization", auth(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(editRequest(exam.examId, student.id, "MAT", 50, 60)))
        ).andExpect(status().isConflict)

        // Lock the exam.
        mockMvc.perform(
            post("/traditional/exams/${exam.examId}/marks").header("Authorization", auth(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(listOf(MarkEntryDto(student.id.toString(), "MAT", 50))))
        ).andExpect(status().isOk)
        mockMvc.perform(
            post("/traditional/exams/${exam.examId}/confirm").param("grade", "Grade 5").header("Authorization", auth(token))
        ).andExpect(status().isNoContent)
        mockMvc.perform(post("/traditional/exams/${exam.examId}/pre-final").header("Authorization", auth(token)))
            .andExpect(status().isOk)
        mockMvc.perform(post("/traditional/exams/${exam.examId}/finalize").header("Authorization", auth(token)))
            .andExpect(status().isOk)

        // An out-of-range new score is rejected.
        mockMvc.perform(
            post("/traditional/exams/${exam.examId}/edit-requests").header("Authorization", auth(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(editRequest(exam.examId, student.id, "MAT", 50, 500)))
        ).andExpect(status().isBadRequest)

        // A plain teacher without a permitted sub-role cannot request at all.
        mockMvc.perform(
            post("/traditional/exams/${exam.examId}/edit-requests").header("Authorization", auth(token(plainTeacher)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(editRequest(exam.examId, student.id, "MAT", 50, 60)))
        ).andExpect(status().isForbidden)

        // A valid coordinator request is created and can be decided exactly once.
        val created = objectMapper.readValue(
            mockMvc.perform(
                post("/traditional/exams/${exam.examId}/edit-requests").header("Authorization", auth(token))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(editRequest(exam.examId, student.id, "MAT", 50, 60)))
            ).andExpect(status().isOk).andReturn().response.contentAsString,
            EditRequestDto::class.java,
        )
        check(created.status == TraditionalEditStatus.PENDING)
        mockMvc.perform(post("/traditional/edit-requests/${created.id}/approve").header("Authorization", auth(token(approver))))
            .andExpect(status().isOk)
        // Replay is idempotent, but a decision cannot be reversed.
        mockMvc.perform(post("/traditional/edit-requests/${created.id}/approve").header("Authorization", auth(token(approver))))
            .andExpect(status().isOk)
        mockMvc.perform(post("/traditional/edit-requests/${created.id}/deny").header("Authorization", auth(token(approver))))
            .andExpect(status().isConflict)
    }

    @Test
    fun `class teacher cannot capture their own class but can capture other classes`() {
        val teacher = user(Role.TEACHER, "Class Teacher", "0700000900", grade = "Grade 3", subRole = SubRole.CTEACHER)
        val coordinator = user(Role.TEACHER, "Coordinator Own", "0700000901", grade = "Grade 3", subRole = SubRole.GRADE_COORDINATOR)
        val ownStudent = user(Role.STUDENT, "Own Class Student", "0700000902", grade = "Grade 3")
        val otherStudent = user(Role.STUDENT, "Other Class Student", "0700000903", grade = "Grade 3")
        val teacherToken = token(teacher)
        val coordinatorToken = token(coordinator)

        val ownedClass = TeacherClassEntity().apply {
            teacherUserId = teacher.id
            this.schoolId = this@TraditionalExamWebTests.schoolId
            name = "Grade 3A"
            gradeLevel = "Grade 3"
            subject = "Mathematics"
            isActive = true
        }
        teacherClassRepository.save(ownedClass)
        classMembershipRepository.save(ClassMembershipEntity().apply {
            classId = ownedClass.id
            studentId = ownStudent.id
        })
        val otherClass = TeacherClassEntity().apply {
            teacherUserId = coordinator.id
            this.schoolId = this@TraditionalExamWebTests.schoolId
            name = "Grade 3B"
            gradeLevel = "Grade 3"
            subject = "Mathematics"
            isActive = true
        }
        teacherClassRepository.save(otherClass)
        classMembershipRepository.save(ClassMembershipEntity().apply {
            classId = otherClass.id
            studentId = otherStudent.id
        })

        val exam = createExam(coordinatorToken, "Grade 3", listOf(SubjectConfigDto("MAT", "Mathematics", 100, TraditionalSubjectType.SINGLE)))

        // The non-negotiable rule: never the class teacher's own class.
        mockMvc.perform(
            post("/traditional/exams/${exam.examId}/marks").header("Authorization", auth(teacherToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(listOf(MarkEntryDto(ownStudent.id.toString(), "MAT", 70))))
        ).andExpect(status().isForbidden)
        // Other classes in the same grade remain capturable.
        mockMvc.perform(
            post("/traditional/exams/" + exam.examId + "/marks").header("Authorization", auth(teacherToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        listOf(MarkEntryDto(otherStudent.id.toString(), "MAT", 70, classId = otherClass.id.toString()))
                    )
                )
        ).andExpect(status().isOk)
        // A supplied classId is authoritative: a learner who is not in that class is rejected.
        mockMvc.perform(
            post("/traditional/exams/" + exam.examId + "/marks").header("Authorization", auth(teacherToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        listOf(MarkEntryDto(otherStudent.id.toString(), "MAT", 70, classId = ownedClass.id.toString()))
                    )
                )
        ).andExpect(status().isBadRequest)

        // The class picker lists other classes, marks the own class not allowed, and
        // the own roster cannot be opened for capture.
        val classes = objectMapper.readValue(
            mockMvc.perform(get("/traditional/exams/${exam.examId}/capture-classes").header("Authorization", auth(teacherToken)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            Array<TraditionalClassDto>::class.java,
        )
        check(classes.none { it.name == "Grade 3A" }) { "the caller's own class must not be offered for capture: " + classes.map { it.name } }
        check(classes.first { it.name == "Grade 3B" }.allowed)
        mockMvc.perform(
            get("/traditional/exams/${exam.examId}/students").param("grade", "Grade 3A")
                .header("Authorization", auth(teacherToken))
        ).andExpect(status().isForbidden)
        val otherRoster = objectMapper.readValue(
            mockMvc.perform(
                get("/traditional/exams/${exam.examId}/students").param("grade", "Grade 3B")
                    .header("Authorization", auth(teacherToken))
            ).andExpect(status().isOk).andReturn().response.contentAsString,
            Array<StudentDto>::class.java,
        )
        check(otherRoster.map { it.id } == listOf(otherStudent.id.toString()))

        // The web access probe mirrors the same refusal.
        mockMvc.perform(
            post("/traditional/exams/${exam.examId}/validate-access").header("Authorization", auth(teacherToken))
                .contentType(MediaType.APPLICATION_JSON).content("{\"grade\":\"Grade 3A\"}")
        ).andExpect(status().isOk)
        val blocked = objectMapper.readValue(
            mockMvc.perform(
                post("/traditional/exams/${exam.examId}/validate-access").header("Authorization", auth(teacherToken))
                    .contentType(MediaType.APPLICATION_JSON).content("{\"grade\":\"Grade 3A\"}")
            ).andReturn().response.contentAsString,
            ValidateAccessResponse::class.java,
        )
        check(!blocked.allowed)
    }

    /**
     * The grant body carries only the pair the grant is about. The permission id, grantor,
     * expiry and used flag are server-owned, so a client that manages to omit them must
     * still be able to grant (and an older client that sends them is ignored).
     */
    @Test
    fun `granting an edit permission needs only the student and teacher`() {
        val coordinator = user(Role.TEACHER, "Coordinator Grant", "0700000500", grade = "Grade 8", subRole = SubRole.GRADE_COORDINATOR)
        val teacher = user(Role.TEACHER, "Teacher Grant", "0700000501", grade = "Grade 8")
        val student = user(Role.STUDENT, "Pupil Grant", "0700000502", grade = "Grade 8", admission = "ADM-301")
        val coordinatorToken = token(coordinator)

        val created = objectMapper.readValue(
            mockMvc.perform(
                post("/traditional/exams").header("Authorization", auth(coordinatorToken))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        objectMapper.writeValueAsString(
                            CreateTraditionalExamRequest(
                                examId = "TRAD_Grade8_OPENER_2026_T1",
                                title = "Opener Term 1 2026 - Grade 8",
                                term = ExamTerm.TERM_1,
                                gradeLevel = "Grade 8",
                                year = 2026,
                                subjects = listOf(SubjectConfigDto("MAT", "Mathematics", 100, TraditionalSubjectType.SINGLE)),
                            )
                        )
                    )
            ).andExpect(status().isOk).andReturn().response.contentAsString,
            TraditionalExamDto::class.java,
        )

        mockMvc.perform(
            post("/traditional/exams/${created.examId}/edit-permission/grant")
                .header("Authorization", auth(coordinatorToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"studentId\":\"${student.id}\",\"teacherId\":\"${teacher.id}\"}")
        ).andExpect(status().isNoContent)
    }

    @Test
    fun `capture session creation is coordinator-only, broadcasts and defaults remarks`() {
        val classTeacher = user(Role.TEACHER, "Class Teacher Four", "0700001100", grade = "Grade 4", subRole = SubRole.CTEACHER)
        val coordinator = user(Role.TEACHER, "Coordinator Four", "0700001101", grade = "Grade 4", subRole = SubRole.GRADE_COORDINATOR)
        val teacherToken = token(classTeacher)
        val coordinatorToken = token(coordinator)
        teacherClassRepository.save(TeacherClassEntity().apply {
            teacherUserId = classTeacher.id
            this.schoolId = this@TraditionalExamWebTests.schoolId
            name = "Grade 4 East"
            gradeLevel = "Grade 4"
            subject = "Mathematics"
            isActive = true
        })
        val request = CreateTraditionalExamRequest(
            examId = "TRAD_SESSION_Grade4_TEST",
            title = "Opener Term 2 2026 - Grade 4",
            term = ExamTerm.TERM_2,
            gradeLevel = "Grade 4",
            year = 2026,
            subjects = listOf(SubjectConfigDto("MAT", "Mathematics", 100, TraditionalSubjectType.SINGLE)),
            remarks = "  ",
        )
        // A class teacher receives sessions; only a coordinator authors one.
        mockMvc.perform(
            post("/traditional/exams").header("Authorization", auth(teacherToken))
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(request))
        ).andExpect(status().isForbidden)

        val created = objectMapper.readValue(
            mockMvc.perform(
                post("/traditional/exams").header("Authorization", auth(coordinatorToken))
                    .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(request))
            ).andExpect(status().isOk).andReturn().response.contentAsString,
            TraditionalExamDto::class.java,
        )
        check(created.sessionRemarks == "Capture scores for Opener Term 2 2026 - Grade 4.")
        check(created.omitPolicy == TraditionalOmitPolicy.OMIT_ALL_INCOMPLETE)
        check(!created.confirmedByMe)

        val notifications = objectMapper.readValue(
            mockMvc.perform(get("/notifications").param("userId", classTeacher.id.toString()).header("Authorization", auth(teacherToken)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            Array<AppNotificationPayload>::class.java,
        )
        val sessionNote = notifications.first { it.title.contains("New paper exam session") }
        check(sessionNote.actionRoute == "exam_status/" + created.examId) {
            "paper-exam notification must open the session, was " + sessionNote.actionRoute
        }

        // Grade-aware defaults replace the old hard-coded four-subject fallback.
        val lower = objectMapper.readValue(
            mockMvc.perform(get("/traditional/grades/Grade 1/subjects").header("Authorization", auth(coordinatorToken)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            Array<SubjectConfigDto>::class.java,
        )
        check(lower.any { it.name == "Environmental Activities" })
        val junior = objectMapper.readValue(
            mockMvc.perform(get("/traditional/grades/Grade 7/subjects").header("Authorization", auth(coordinatorToken)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            Array<SubjectConfigDto>::class.java,
        )
        check(junior.any { it.name == "Pre-Technical Studies" })
    }

    @Test
    fun `coordinator re-edits a saved session and a class teacher cannot`() {
        val coordinator = user(Role.TEACHER, "Coordinator Edit Session", "0700001150", grade = "Grade 4", subRole = SubRole.GRADE_COORDINATOR)
        val teacher = user(Role.TEACHER, "Class Teacher Edit Session", "0700001151", grade = "Grade 4", subRole = SubRole.CTEACHER)
        val coordinatorToken = token(coordinator)
        val teacherToken = token(teacher)
        val exam = createExam(coordinatorToken, "Grade 4", listOf(SubjectConfigDto("MAT", "Mathematics", 100, TraditionalSubjectType.SINGLE)))
        val update = """{"title":"Mid Term 2 2026 - Grade 4","remarks":"Use the mid-term paper","omitPolicy":"OMIT_NO_SCORES"}"""
        mockMvc.perform(
            put("/traditional/exams/${exam.examId}").header("Authorization", auth(teacherToken))
                .contentType(MediaType.APPLICATION_JSON).content(update)
        ).andExpect(status().isForbidden)
        val updated = objectMapper.readValue(
            mockMvc.perform(
                put("/traditional/exams/${exam.examId}").header("Authorization", auth(coordinatorToken))
                    .contentType(MediaType.APPLICATION_JSON).content(update)
            ).andExpect(status().isOk).andReturn().response.contentAsString,
            TraditionalExamDto::class.java,
        )
        check(updated.title == "Mid Term 2 2026 - Grade 4")
        check(updated.sessionRemarks == "Use the mid-term paper")
        check(updated.omitPolicy == TraditionalOmitPolicy.OMIT_NO_SCORES)
    }

    @Test
    fun `capture summary counts learners without exposing every mark`() {
        val coordinator = user(Role.TEACHER, "Coordinator Summary", "0700001200", grade = "Grade 6", subRole = SubRole.GRADE_COORDINATOR)
        val complete = user(Role.STUDENT, "Complete Learner", "0700001201", grade = "Grade 6")
        val partial = user(Role.STUDENT, "Partial Learner", "0700001202", grade = "Grade 6")
        val none = user(Role.STUDENT, "No Score Learner", "0700001203", grade = "Grade 6")
        val token = token(coordinator)
        val exam = createExam(
            token, "Grade 6",
            listOf(
                SubjectConfigDto("MAT", "Mathematics", 100, TraditionalSubjectType.SINGLE),
                SubjectConfigDto("ENG", "English", 100, TraditionalSubjectType.SINGLE),
            ),
        )
        val entries = listOf(
            MarkEntryDto(complete.id.toString(), "MAT", 80),
            MarkEntryDto(complete.id.toString(), "ENG", 70),
            MarkEntryDto(partial.id.toString(), "MAT", 60),
        )
        mockMvc.perform(
            post("/traditional/exams/${exam.examId}/marks").header("Authorization", auth(token))
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(entries))
        ).andExpect(status().isOk)

        val summary = objectMapper.readValue(
            mockMvc.perform(get("/traditional/exams/${exam.examId}/capture-summary").header("Authorization", auth(token)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            CaptureSummaryDto::class.java,
        )
        check(summary.totalStudents == 3)
        check(summary.studentsWithMarks == 2)
        check(summary.studentsWithEmptyFields == 1)
        check(summary.studentsWithNoScores == 1)
        check(!summary.myConfirmed)
        val statuses = summary.classes.flatMap { it.students }.associate { it.studentId to it.status }
        check(statuses[complete.id.toString()] == CaptureStudentStatus.COMPLETE)
        check(statuses[partial.id.toString()] == CaptureStudentStatus.PARTIAL)
        check(statuses[none.id.toString()] == CaptureStudentStatus.NONE)

        mockMvc.perform(
            post("/traditional/exams/${exam.examId}/confirm").param("grade", "Grade 6").header("Authorization", auth(token))
        ).andExpect(status().isNoContent)
        val confirmedSummary = objectMapper.readValue(
            mockMvc.perform(get("/traditional/exams/${exam.examId}/capture-summary").header("Authorization", auth(token)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            CaptureSummaryDto::class.java,
        )
        check(confirmedSummary.myConfirmed)
        val confirmedExam = objectMapper.readValue(
            mockMvc.perform(get("/traditional/exams/" + exam.examId).header("Authorization", auth(token)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            TraditionalExamDto::class.java,
        )
        check(confirmedExam.submittedAt != null)
        val stored = examRepository.findById(UUID.fromString(exam.examId)).orElseThrow()
        check(stored.broadcastAt != null)
    }

    @Test
    fun `finalize applies omit policy and the ten percent subject-column rule`() {
        val coordinator = user(Role.TEACHER, "Coordinator Omit", "0700001300", grade = "Grade 5", subRole = SubRole.GRADE_COORDINATOR)
        val token = token(coordinator)
        val students = (1..11).map { index ->
            user(Role.STUDENT, "Omit Learner $index", "07000013$index", grade = "Grade 5", admission = "OM-$index")
        }
        val exam = createExam(
            token, "Grade 5",
            listOf(
                SubjectConfigDto("MAT", "Mathematics", 100, TraditionalSubjectType.SINGLE),
                SubjectConfigDto("SOC", "Social Studies", 100, TraditionalSubjectType.SINGLE),
            ),
        )
        // Mathematics is captured for all 11 learners; Social Studies for 1/11 = 9.1%,
        // under the owner's 10% column threshold.
        val entries = students.map { MarkEntryDto(it.id.toString(), "MAT", 50) } +
            MarkEntryDto(students.first().id.toString(), "SOC", 60)
        mockMvc.perform(
            post("/traditional/exams/${exam.examId}/marks").header("Authorization", auth(token))
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(entries))
        ).andExpect(status().isOk)
        mockMvc.perform(
            post("/traditional/exams/${exam.examId}/confirm").param("grade", "Grade 5").header("Authorization", auth(token))
        ).andExpect(status().isNoContent)
        mockMvc.perform(post("/traditional/exams/${exam.examId}/pre-final").header("Authorization", auth(token)))
            .andExpect(status().isOk)
        mockMvc.perform(
            post("/traditional/exams/${exam.examId}/finalize").param("omitPolicy", "OMIT_NO_SCORES")
                .header("Authorization", auth(token))
        ).andExpect(status().isOk)

        val analytics = objectMapper.readValue(
            mockMvc.perform(get("/traditional/exams/${exam.examId}/analytics").header("Authorization", auth(token)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            TraditionalExamAnalyticsDto::class.java,
        )
        check(analytics.totalStudents == 11)
        check(analytics.subjectAverages.keys == setOf("MAT"))
        check(analytics.excludedSubjects.contains("Social Studies"))
        val stored = examRepository.findById(UUID.fromString(exam.examId)).orElseThrow()
        check(stored.analyticsSnapshot != null) { "finalize must persist a durable analytics snapshot" }

        // Finalize signals the coordinator/admin that analytics are ready.
        val notifications = objectMapper.readValue(
            mockMvc.perform(get("/notifications").param("userId", coordinator.id.toString()).header("Authorization", auth(token)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            Array<AppNotificationPayload>::class.java,
        )
        check(notifications.any { it.title == "Analytics have been completed" })
    }

    @Test
    fun `default omit-all-incomplete drops a partial learner from analysis`() {
        val coordinator = user(Role.TEACHER, "Coordinator Incomplete", "0700001400", grade = "Grade 5", subRole = SubRole.GRADE_COORDINATOR)
        val complete = user(Role.STUDENT, "Complete Omit", "0700001401", grade = "Grade 5")
        val partial = user(Role.STUDENT, "Partial Omit", "0700001402", grade = "Grade 5")
        val token = token(coordinator)
        val exam = createExam(
            token, "Grade 5",
            listOf(
                SubjectConfigDto("MAT", "Mathematics", 100, TraditionalSubjectType.SINGLE),
                SubjectConfigDto("ENG", "English", 100, TraditionalSubjectType.SINGLE),
            ),
        )
        mockMvc.perform(
            post("/traditional/exams/${exam.examId}/marks").header("Authorization", auth(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        listOf(
                            MarkEntryDto(complete.id.toString(), "MAT", 80),
                            MarkEntryDto(complete.id.toString(), "ENG", 70),
                            MarkEntryDto(partial.id.toString(), "MAT", 60),
                        )
                    )
                )
        ).andExpect(status().isOk)
        mockMvc.perform(
            post("/traditional/exams/${exam.examId}/confirm").param("grade", "Grade 5").header("Authorization", auth(token))
        ).andExpect(status().isNoContent)
        mockMvc.perform(post("/traditional/exams/${exam.examId}/pre-final").header("Authorization", auth(token)))
            .andExpect(status().isOk)
        mockMvc.perform(
            post("/traditional/exams/${exam.examId}/finalize").header("Authorization", auth(token))
        ).andExpect(status().isOk)
        val analytics = objectMapper.readValue(
            mockMvc.perform(get("/traditional/exams/${exam.examId}/analytics").header("Authorization", auth(token)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            TraditionalExamAnalyticsDto::class.java,
        )
        check(analytics.totalStudents == 1)
        check(analytics.omittedStudents == 1)
        check(analytics.topPerformers.single().studentId == complete.id.toString())
    }

    @Test
    fun `coordinator review edit notifies the class teacher and is allowed at pre-final`() {
        val classTeacher = user(Role.TEACHER, "Class Teacher Review", "0700001500", grade = "Grade 7", subRole = SubRole.CTEACHER)
        val coordinator = user(Role.TEACHER, "Coordinator Review", "0700001501", grade = "Grade 7", subRole = SubRole.GRADE_COORDINATOR)
        val complete = user(Role.STUDENT, "Review Complete", "0700001502", grade = "Grade 7")
        val missing = user(Role.STUDENT, "Review Missing", "0700001503", grade = "Grade 7")
        val teacherToken = token(classTeacher)
        val coordinatorToken = token(coordinator)
        val teacherClass = teacherClassRepository.save(TeacherClassEntity().apply {
            teacherUserId = classTeacher.id
            this.schoolId = this@TraditionalExamWebTests.schoolId
            name = "Grade 7 West"
            gradeLevel = "Grade 7"
            subject = "Mathematics"
            isActive = true
        })
        listOf(complete, missing).forEach { student ->
            classMembershipRepository.save(ClassMembershipEntity().apply {
                classId = teacherClass.id
                studentId = student.id
            })
        }
        val exam = createExam(
            coordinatorToken, "Grade 7",
            listOf(
                SubjectConfigDto("MAT", "Mathematics", 100, TraditionalSubjectType.SINGLE),
                SubjectConfigDto("ENG", "English", 100, TraditionalSubjectType.SINGLE),
            ),
        )
        mockMvc.perform(
            post("/traditional/exams/${exam.examId}/marks").header("Authorization", auth(coordinatorToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        listOf(
                            MarkEntryDto(complete.id.toString(), "MAT", 80),
                            MarkEntryDto(complete.id.toString(), "ENG", 70),
                        )
                    )
                )
        ).andExpect(status().isOk)
        mockMvc.perform(
            post("/traditional/exams/${exam.examId}/confirm").param("grade", "Grade 7").header("Authorization", auth(coordinatorToken))
        ).andExpect(status().isNoContent)
        mockMvc.perform(post("/traditional/exams/${exam.examId}/pre-final").header("Authorization", auth(coordinatorToken)))
            .andExpect(status().isOk)

        // Coordinator adds the missing learner's marks from the review panel.
        mockMvc.perform(
            post("/traditional/exams/${exam.examId}/marks").header("Authorization", auth(coordinatorToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        listOf(
                            MarkEntryDto(missing.id.toString(), "MAT", 55),
                            MarkEntryDto(missing.id.toString(), "ENG", 65),
                        )
                    )
                )
        ).andExpect(status().isOk)

        val notifications = objectMapper.readValue(
            mockMvc.perform(get("/notifications").param("userId", classTeacher.id.toString()).header("Authorization", auth(teacherToken)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            Array<AppNotificationPayload>::class.java,
        )
        check(notifications.any { it.title == "Score edited by grade coordinator" && it.message.contains("Review Missing") })
    }

    // ------------------------------------------------------------ report-v1 data slice

    private fun saveMarks(token: String, examId: String, entries: List<MarkEntryDto>): List<TraditionalMarkDto> =
        objectMapper.readValue(
            mockMvc.perform(
                post("/traditional/exams/" + examId + "/marks").header("Authorization", auth(token))
                    .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(entries))
            ).andExpect(status().isOk).andReturn().response.contentAsString,
            Array<TraditionalMarkDto>::class.java,
        ).toList()

    private fun examWithGrading(token: String, examId: String): TraditionalExamDto = objectMapper.readValue(
        mockMvc.perform(get("/traditional/exams/" + examId).header("Authorization", auth(token)))
            .andExpect(status().isOk).andReturn().response.contentAsString,
        TraditionalExamDto::class.java,
    )

    private fun classEntity(teacher: UserEntity, name: String, grade: String, subject: String): TeacherClassEntity =
        teacherClassRepository.save(TeacherClassEntity().apply {
            teacherUserId = teacher.id
            this.schoolId = this@TraditionalExamWebTests.schoolId
            this.name = name
            this.gradeLevel = grade
            this.subject = subject
            isActive = true
        })

    private fun enrol(student: UserEntity, clazz: TeacherClassEntity) {
        classMembershipRepository.save(ClassMembershipEntity().apply {
            classId = clazz.id
            studentId = student.id
        })
    }

    /** The school's own split, not the platform defaults: 75 % is a ME here but an EE on 80/65. */
    private fun schoolBands(): GradingConfigDto = GradingConfigDto(
        bands = listOf(
            GradingBandDto("EE", 90),
            GradingBandDto("ME", 70),
            GradingBandDto("AE", 50),
            GradingBandDto("BE", 30),
            GradingBandDto("E", 0),
        ),
    )

    @Test
    fun `capture session snapshots its grading bands and a later grade setting cannot re-band it`() {
        val coordinator = user(Role.TEACHER, "Coordinator Bands", "0700001600", grade = "Grade 9", subRole = SubRole.GRADE_COORDINATOR)
        val student = user(Role.STUDENT, "Band Learner", "0700001601", grade = "Grade 9")
        val token = token(coordinator)
        val sessionBands = schoolBands()
        val exam = createExam(
            token, "Grade 9",
            listOf(SubjectConfigDto("MAT", "Mathematics", 100, TraditionalSubjectType.SINGLE)),
            grading = sessionBands,
        )
        check(exam.grading?.bands == sessionBands.bands) { "the create response must echo the resolved snapshot" }

        // The coordinator's list is saved back as the grade's last-used config...
        val lastUsed = objectMapper.readValue(
            mockMvc.perform(get("/traditional/grades/Grade 9/grading").header("Authorization", auth(token)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            GradingConfigDto::class.java,
        )
        check(lastUsed.bands == sessionBands.bands)

        // ...so the next session created without an explicit list resolves it.
        val second = createExam(token, "Grade 9", listOf(SubjectConfigDto("MAT", "Mathematics", 100, TraditionalSubjectType.SINGLE)))
        check(second.grading?.bands == sessionBands.bands)

        // Marks are banded by the session snapshot: 75 % >= 70 is ME (it would be AE on the defaults).
        val saved = saveMarks(token, exam.examId, listOf(MarkEntryDto(student.id.toString(), "MAT", 75)))
        check(saved.single().gradeBand == "ME")

        // The coordinator later changes the grade setting.
        mockMvc.perform(
            post("/traditional/grades/Grade 9/grading").header("Authorization", auth(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        GradingConfigDto(bands = listOf(GradingBandDto("EE", 60), GradingBandDto("E", 0)))
                    )
                )
        ).andExpect(status().isNoContent)

        // The old exam keeps its session snapshot, both on read and for later mark writes.
        check(examWithGrading(token, exam.examId).grading?.bands == sessionBands.bands)
        val rebanded = saveMarks(token, exam.examId, listOf(MarkEntryDto(student.id.toString(), "MAT", 75)))
        check(rebanded.single().gradeBand == "ME")

        // A new session prefills from the changed setting.
        val third = createExam(token, "Grade 9", listOf(SubjectConfigDto("MAT", "Mathematics", 100, TraditionalSubjectType.SINGLE)))
        check(third.grading?.bands == listOf(GradingBandDto("EE", 60), GradingBandDto("E", 0)))

        // Editing a session may replace its bands (and the last-used config) explicitly.
        val editedBands = GradingConfigDto(bands = listOf(GradingBandDto("A", 50), GradingBandDto("B", 0)))
        val updated = objectMapper.readValue(
            mockMvc.perform(
                put("/traditional/exams/" + third.examId).header("Authorization", auth(token))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(UpdateTraditionalExamRequest(grading = editedBands)))
            ).andExpect(status().isOk).andReturn().response.contentAsString,
            TraditionalExamDto::class.java,
        )
        check(updated.grading?.bands == editedBands.bands)
    }

    @Test
    fun `invalid session bands are rejected before a session is created`() {
        val coordinator = user(Role.TEACHER, "Coordinator Bad Bands", "0700001610", grade = "Grade 9", subRole = SubRole.GRADE_COORDINATOR)
        val token = token(coordinator)
        val subjects = listOf(SubjectConfigDto("MAT", "Mathematics", 100, TraditionalSubjectType.SINGLE))
        val invalid = listOf(
            // duplicate threshold
            GradingConfigDto(bands = listOf(GradingBandDto("EE", 80), GradingBandDto("ME", 80), GradingBandDto("E", 0))),
            // duplicate label
            GradingConfigDto(bands = listOf(GradingBandDto("EE", 80), GradingBandDto("ee", 60), GradingBandDto("E", 0))),
            // threshold outside 0..100
            GradingConfigDto(bands = listOf(GradingBandDto("EE", 120), GradingBandDto("E", 0))),
            // no bands at all
            GradingConfigDto(bands = emptyList()),
        )
        invalid.forEachIndexed { index, grading ->
            val request = CreateTraditionalExamRequest(
                examId = "TRAD_BAD_BANDS_" + index,
                title = "Bad bands session " + index,
                term = ExamTerm.TERM_2,
                gradeLevel = "Grade 9",
                year = 2026,
                subjects = subjects,
                grading = grading,
            )
            mockMvc.perform(
                post("/traditional/exams").header("Authorization", auth(token))
                    .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(request))
            ).andExpect(status().isBadRequest)
        }
    }

    @Test
    fun `overall level is the band of the mean percentage not the raw score total`() {
        val coordinator = user(Role.TEACHER, "Coordinator Mean Band", "0700001700", grade = "Grade 10", subRole = SubRole.GRADE_COORDINATOR)
        val student = user(Role.STUDENT, "Mean Band Learner", "0700001701", grade = "Grade 10")
        val coordinatorToken = token(coordinator)
        val studentToken = token(student)
        val subjects = (1..8).map { SubjectConfigDto("S" + it, "Subject " + it, 100, TraditionalSubjectType.SINGLE) }
        val exam = createExam(coordinatorToken, "Grade 10", subjects)
        saveMarks(
            coordinatorToken, exam.examId,
            listOf(45, 45, 45, 45, 44, 44, 44, 44).mapIndexed { index, score ->
                MarkEntryDto(student.id.toString(), "S" + (index + 1), score)
            },
        )

        // G1.4 reproduction: TOTAL 356 over eight subjects is a 44.5 % mean -> BE. The old raw
        // thresholds (EE >= 350) printed "Overall 44.5 % - Grade EE".
        val ranking = objectMapper.readValue(
            mockMvc.perform(get("/traditional/exams/" + exam.examId + "/grade-wide-ranking").header("Authorization", auth(coordinatorToken)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            Array<StudentGradeRowDto>::class.java,
        )
        check(ranking.single().totalPercentage == 356.0)
        check(ranking.single().overallGrade == "BE")

        publishExam(exam.examId, coordinatorToken, coordinator.id)
        val report = objectMapper.readValue(
            mockMvc.perform(get("/traditional/exams/" + exam.examId + "/results/me").header("Authorization", auth(studentToken)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            TraditionalStudentReportDto::class.java,
        )
        check(report.overallPercentage == 44.5)
        check(report.overallGrade == "BE")
    }

    @Test
    fun `grade analysis follows exam order, counts complete learners and reconciles the teacher bars`() {
        val coordinator = user(Role.TEACHER, "Coordinator Analysis", "0700001800", grade = "Grade 4", subRole = SubRole.GRADE_COORDINATOR)
        val token = token(coordinator)
        val classNames = listOf("Grade 4 Lion", "Grade 4 Buffalo", "Grade 4 Elephant", "Grade 4 Rhino", "Grade 4 Leopard")
        // One Kiswahili teacher per class: five bars for Kiswahili, none for Mathematics.
        val classTeachers = classNames.mapIndexed { index, _ ->
            user(
                Role.TEACHER, "Kiswahili Teacher " + (index + 1),
                "07000018%02d".format(index + 1), grade = "Grade 4", subRole = SubRole.CTEACHER,
            )
        }
        val classes = classNames.mapIndexed { index, name -> classEntity(classTeachers[index], name, "Grade 4", "KISW") }
        // No zero mark: a zero subject total would move that learner to the incomplete table
        // (decision 15) and drop them out of the analysis means.
        val kisMarks = listOf(listOf(80, 90), listOf(70, 60), listOf(50, 40), listOf(30, 20), listOf(10, 20))
        val learners = classes.mapIndexed { classIndex, clazz ->
            (0..1).map { seat ->
                val student = user(
                    Role.STUDENT, "Analysis Learner " + classIndex + "-" + seat,
                    "07000019%02d".format(classIndex * 2 + seat), grade = "Grade 4",
                )
                enrol(student, clazz)
                student
            }
        }
        val exam = createExam(
            token, "Grade 4",
            listOf(
                SubjectConfigDto("MAT", "Mathematics", 100, TraditionalSubjectType.SINGLE),
                SubjectConfigDto("KIS", "Kiswahili", 100, TraditionalSubjectType.SINGLE),
            ),
        )
        saveMarks(
            token, exam.examId,
            learners.flatMapIndexed { classIndex, classLearners ->
                classLearners.flatMapIndexed { seat, student ->
                    listOf(
                        MarkEntryDto(student.id.toString(), "KIS", kisMarks[classIndex][seat]),
                        MarkEntryDto(student.id.toString(), "MAT", 50),
                    )
                }
            },
        )

        val analysis = objectMapper.readValue(
            mockMvc.perform(get("/traditional/exams/" + exam.examId + "/grade-analysis").header("Authorization", auth(token)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            TraditionalGradeAnalysisDto::class.java,
        )
        // D10: subject order is the exam's, and the maps follow it.
        check(analysis.subjects == listOf("Mathematics", "Kiswahili"))
        check(analysis.classes.size == 5)
        analysis.classes.forEach { check(it.subjectAverages.keys.toList() == listOf("Mathematics", "Kiswahili")) }
        // F25: the class teacher comes from the class row, never a blank cell.
        check(analysis.classes.all { it.teacherName.isNotBlank() })
        check(analysis.classes.first { it.classTag == "Grade 4 Lion" }.teacherName == "Kiswahili Teacher 1")
        // D3: the ranked (complete) learner count behind each row.
        check(analysis.classes.all { it.studentCount == 2 })
        // Subject means are percentage means; TOTAL is the class mean of learner totals (Σ %).
        val lion = analysis.classes.first { it.classTag == "Grade 4 Lion" }
        check(lion.subjectAverages["Kiswahili"] == 85.0)
        check(lion.subjectAverages["Mathematics"] == 50.0)
        check(lion.totalAverage == 135.0)
        check(lion.overallPosition == 1)
        // Positions are unique sequential and ranked by mean desc.
        check(lion.subjectPositions["Kiswahili"] == 1)
        check(analysis.classes.mapNotNull { it.subjectPositions["Kiswahili"] }.sorted() == listOf(1, 2, 3, 4, 5))
        // D13/D14: five Kiswahili bars whose weighted mean reconciles with the page-1 mean.
        val kis = analysis.subjectTeacherContributions.first { it.subjectName == "Kiswahili" }
        check(kis.gradeMean == 47.0)
        check(kis.teachers.map { it.mean } == listOf(85.0, 65.0, 45.0, 25.0, 15.0))
        check(kis.teachers.all { it.learners == 2 && !it.shared && !it.former })
        check(kis.teachers.first { it.mean == 85.0 }.classTags == listOf("Grade 4 Lion"))
        check(kis.unassigned == null)
        val weighted = kis.teachers.sumOf { it.mean * it.learners } / kis.teachers.sumOf { it.learners }
        check(abs(weighted - kis.gradeMean) < 0.011)
        // No Mathematics assignment in the fixture -> one MUTED Unassigned bar accounts for it.
        val math = analysis.subjectTeacherContributions.first { it.subjectName == "Mathematics" }
        check(math.teachers.isEmpty())
        check(math.unassigned?.learners == 10 && math.unassigned?.mean == 50.0)
        // D18: the analysis carries the session bands for the legend.
        check(analysis.grading != null)

        // D11/F5: per-class tables use class positions, not grade-wide ranks.
        val perClass = objectMapper.readValue(
            mockMvc.perform(get("/traditional/exams/" + exam.examId + "/per-class-rankings").header("Authorization", auth(token)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            object : TypeReference<Map<String, List<StudentGradeRowDto>>>() {},
        )
        check(perClass.keys == classNames.toSet())
        perClass.forEach { (_, rows) -> check(rows.map { it.rank } == listOf(1, 2)) }
        check(perClass.getValue("Grade 4 Lion").first().totalPercentage == 140.0)

        // A26: the public class-teacher read the per-class report header uses (raw class tags).
        val teachersByClass = service.classTeachers(
            CurrentUser(coordinator.id, Role.TEACHER, SubRole.GRADE_COORDINATOR),
            exam.examId,
        )
        check(teachersByClass == classNames.mapIndexed { index, name -> name to classTeachers[index].name }.toMap())
    }

    @Test
    fun `incomplete mark correction needs the current mark and a positive replacement`() {
        val coordinator = user(Role.TEACHER, "Coordinator Correct", "0700000950", grade = "Grade 5", subRole = SubRole.GRADE_COORDINATOR)
        val zeroLearner = user(Role.STUDENT, "Zero Learner", "0700000951", grade = "Grade 5")
        val token = token(coordinator)
        val exam = createExam(token, "Grade 5", listOf(SubjectConfigDto("MAT", "Mathematics", 100, TraditionalSubjectType.SINGLE)))
        mockMvc.perform(
            post("/traditional/exams/" + exam.examId + "/marks").header("Authorization", auth(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(listOf(MarkEntryDto(zeroLearner.id.toString(), "MAT", 0))))
        ).andExpect(status().isOk)
        mockMvc.perform(
            post("/traditional/exams/" + exam.examId + "/confirm").param("grade", "Grade 5").header("Authorization", auth(token))
        ).andExpect(status().isNoContent)
        mockMvc.perform(post("/traditional/exams/" + exam.examId + "/pre-final").header("Authorization", auth(token)))
            .andExpect(status().isOk)
        mockMvc.perform(post("/traditional/exams/" + exam.examId + "/finalize").header("Authorization", auth(token)))
            .andExpect(status().isOk)

        // D15: a stale old score (the mark is 0, the request claims 7) is rejected instead of
        // approving a correction against a mark the teacher never saw.
        mockMvc.perform(
            post("/traditional/exams/" + exam.examId + "/edit-requests").header("Authorization", auth(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(editRequest(exam.examId, zeroLearner.id, "MAT", 7, 1)))
        ).andExpect(status().isConflict)

        // A zero/missing mark corrected to 0 would leave the learner incomplete forever.
        mockMvc.perform(
            post("/traditional/exams/" + exam.examId + "/edit-requests").header("Authorization", auth(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(editRequest(exam.examId, zeroLearner.id, "MAT", 0, 0)))
        ).andExpect(status().isBadRequest)

        // Valid correction: 0 -> 1, approved with a reviewed-at/requester audit trail.
        val created = objectMapper.readValue(
            mockMvc.perform(
                post("/traditional/exams/" + exam.examId + "/edit-requests").header("Authorization", auth(token))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(editRequest(exam.examId, zeroLearner.id, "MAT", 0, 1)))
            ).andExpect(status().isOk).andReturn().response.contentAsString,
            EditRequestDto::class.java,
        )
        check(created.status == TraditionalEditStatus.PENDING)
        check(created.oldScore == 0 && created.newScore == 1)
        check(created.teacherId == coordinator.id.toString())
        val approved = objectMapper.readValue(
            mockMvc.perform(
                post("/traditional/edit-requests/" + created.id + "/approve").header("Authorization", auth(token))
            ).andExpect(status().isOk).andReturn().response.contentAsString,
            EditRequestDto::class.java,
        )
        check(approved.status == TraditionalEditStatus.APPROVED)
        check(approved.reviewedBy != null && approved.reviewedAt != null)

        // The approved correction is applied through the normal mark entry; the learner then has
        // a real mark and is no longer an incomplete row in the report data.
        mockMvc.perform(
            post("/traditional/exams/" + exam.examId + "/marks").header("Authorization", auth(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(listOf(MarkEntryDto(zeroLearner.id.toString(), "MAT", 1))))
        ).andExpect(status().isOk)
        val rows = objectMapper.readValue(
            mockMvc.perform(get("/traditional/exams/" + exam.examId + "/grade-wide-ranking").header("Authorization", auth(token)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            Array<StudentGradeRowDto>::class.java,
        )
        val corrected = rows.single { it.studentId == zeroLearner.id.toString() }
        check(corrected.subjects.single().rawScore == 1)
        check(corrected.subjects.none { it.rawScore == 0 })
    }

    /** A19: fetch the PDF behind the job's signed URL. */
    private fun download(job: ReportJobPayload): ByteArray {
        val uri = URI(job.fileUrl)
        return mockMvc.perform(get(uri.rawPath + "?" + uri.rawQuery))
            .andExpect(status().isOk).andReturn().response.contentAsByteArray
    }

    @Test
    fun `mark writes accept the optional classId for a provisioned roster-only learner`() {
        val classTeacher = user(Role.TEACHER, "Roster Class Teacher", "0700002100", grade = "Grade 3", subRole = SubRole.CTEACHER)
        val coordinator = user(Role.TEACHER, "Roster Coordinator", "0700002101", grade = "Grade 3", subRole = SubRole.GRADE_COORDINATOR)
        val rosterLearner = user(Role.STUDENT, "Roster Learner", "0700002102", grade = "Grade 3")
        val tokenCoord = token(coordinator)

        val clazz = TeacherClassEntity().apply {
            teacherUserId = classTeacher.id
            this.schoolId = this@TraditionalExamWebTests.schoolId
            name = "Grade 3R"
            gradeLevel = "Grade 3"
            subject = "Mathematics"
            isActive = true
        }
        teacherClassRepository.save(clazz)
        val otherClass = TeacherClassEntity().apply {
            teacherUserId = classTeacher.id
            this.schoolId = this@TraditionalExamWebTests.schoolId
            name = "Grade 3S"
            gradeLevel = "Grade 3"
            subject = "Mathematics"
            isActive = true
        }
        teacherClassRepository.save(otherClass)
        // G1.2: imported learners carry provisioned_class_id instead of a membership row.
        rosterLearner.provisionedClassId = clazz.id
        userRepository.save(rosterLearner)

        val exam = createExam(tokenCoord, "Grade 3", listOf(SubjectConfigDto("MAT", "Mathematics", 100, TraditionalSubjectType.SINGLE)))
        mockMvc.perform(
            post("/traditional/exams/" + exam.examId + "/marks").header("Authorization", auth(tokenCoord))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(listOf(MarkEntryDto(rosterLearner.id.toString(), "MAT", 66, classId = clazz.id.toString()))))
        ).andExpect(status().isOk)

        // A different class is still rejected for the same provisioned learner.
        mockMvc.perform(
            post("/traditional/exams/" + exam.examId + "/marks").header("Authorization", auth(tokenCoord))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(listOf(MarkEntryDto(rosterLearner.id.toString(), "MAT", 67, classId = otherClass.id.toString()))))
        ).andExpect(status().isBadRequest)
    }

    @Test
    fun `learners and parents see every published exam with their own marks across years`() {
        val parent = user(Role.PARENT, "History Parent", "0700002200")
        val learner = user(Role.STUDENT, "History Learner", "0700002201", grade = "Grade 5", parentId = parent.id)
        val other = user(Role.STUDENT, "Other Learner", "0700002202", grade = "Grade 4")
        val coordinator = user(Role.TEACHER, "History Coordinator", "0700002203", grade = "Grade 4", subRole = SubRole.GRADE_COORDINATOR)
        val coordToken = token(coordinator)
        val learnerToken = token(learner)
        val parentToken = token(parent)
        val subjects = listOf(SubjectConfigDto("MAT", "Mathematics", 100, TraditionalSubjectType.SINGLE))

        val previous = createExam(coordToken, "Grade 4", subjects, year = 2025)
        saveMarks(coordToken, previous.examId, listOf(MarkEntryDto(learner.id.toString(), "MAT", 60)))
        publishExam(previous.examId, coordToken, coordinator.id)

        val current = createExam(coordToken, "Grade 4", subjects, year = 2026)
        saveMarks(coordToken, current.examId, listOf(MarkEntryDto(learner.id.toString(), "MAT", 70)))
        publishExam(current.examId, coordToken, coordinator.id)

        val otherLearnerExam = createExam(coordToken, "Grade 4", subjects, year = 2026)
        saveMarks(coordToken, otherLearnerExam.examId, listOf(MarkEntryDto(other.id.toString(), "MAT", 80)))
        publishExam(otherLearnerExam.examId, coordToken, coordinator.id)

        // FINALIZED but unpublished: the learner must never see it.
        val hidden = createExam(coordToken, "Grade 4", subjects, year = 2026)
        saveMarks(coordToken, hidden.examId, listOf(MarkEntryDto(learner.id.toString(), "MAT", 50)))
        mockMvc.perform(
            post("/traditional/exams/" + hidden.examId + "/confirm").param("grade", "Grade 4")
                .header("Authorization", auth(coordToken))
        ).andExpect(status().isNoContent)
        mockMvc.perform(
            post("/traditional/exams/" + hidden.examId + "/pre-final").header("Authorization", auth(coordToken))
        ).andExpect(status().isOk)
        mockMvc.perform(
            post("/traditional/exams/" + hidden.examId + "/finalize").param("coordinatorId", coordinator.id.toString())
                .header("Authorization", auth(coordToken))
        ).andExpect(status().isOk)

        fun ids(token: String): Set<String> = objectMapper.readValue(
            mockMvc.perform(get("/traditional/exams").header("Authorization", auth(token)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            Array<TraditionalExamDto>::class.java,
        ).map { it.examId }.toSet()

        // The learner's current grade is Grade 5; both Grade 4 published exams still appear.
        val learnerIds = ids(learnerToken)
        check(previous.examId in learnerIds && current.examId in learnerIds)
        check(otherLearnerExam.examId !in learnerIds) { "another learner's exam must never be listed" }
        check(hidden.examId !in learnerIds) { "FINALIZED-unpublished exams stay hidden from learners" }
        check(ids(parentToken) == learnerIds)

        // Staff list is unchanged: the coordinator still sees every school session.
        val staffIds = ids(coordToken)
        check(hidden.examId in staffIds && otherLearnerExam.examId in staffIds)
    }

    @Test
    fun `learner re-downloads a published exam report after job rows are pruned`() {
        val learner = user(Role.STUDENT, "Reprint Learner", "0700002300", grade = "Grade 4")
        val coordinator = user(Role.TEACHER, "Reprint Coordinator", "0700002301", grade = "Grade 4", subRole = SubRole.GRADE_COORDINATOR)
        val coordToken = token(coordinator)
        val learnerToken = token(learner)
        val exam = createExam(coordToken, "Grade 4", listOf(SubjectConfigDto("MAT", "Mathematics", 100, TraditionalSubjectType.SINGLE)))
        saveMarks(coordToken, exam.examId, listOf(MarkEntryDto(learner.id.toString(), "MAT", 72)))
        publishExam(exam.examId, coordToken, coordinator.id)

        fun generate(requestId: String): ReportJobPayload {
            val body = objectMapper.writeValueAsString(
                StudentReportRequestPayload(
                    reportType = ReportType.TRADITIONAL_STUDENT,
                    examId = exam.examId,
                    term = "TERM_2",
                    year = 2026,
                    jobRequestId = requestId,
                )
            )
            val response = mockMvc.perform(
                post("/student/reports/generate").header("Authorization", auth(learnerToken))
                    .contentType(MediaType.APPLICATION_JSON).content(body)
            ).andExpect(status().isOk).andReturn().response.contentAsString
            return objectMapper.readValue(response, ReportJobPayload::class.java)
        }

        val first = generate("A19-FIRST")
        check(first.status == ReportJobStatus.READY) { "expected READY, got " + first.status + " " + first.message }
        check(String(download(first), 0, 5, Charsets.ISO_8859_1) == "%PDF-")

        // Simulated 90-day retention prune: the job rows and their rendered files are gone.
        reportJobs.findAll().filter { it.ownerId == learner.id }.forEach { job -> job.storageName?.let(reportStorage::delete) }
        reportJobs.deleteAll()

        // Regeneration is from DB data, never from the stored file, so it still succeeds.
        val again = generate("A19-SECOND")
        check(again.status == ReportJobStatus.READY) { "regeneration after prune failed: " + again.message }
        check(again.fileSize > 0)
        check(String(download(again), 0, 5, Charsets.ISO_8859_1) == "%PDF-")
    }
}
