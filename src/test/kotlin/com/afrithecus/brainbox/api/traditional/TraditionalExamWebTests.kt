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
import com.afrithecus.brainbox.api.traditional.web.TraditionalMarkDto
import com.afrithecus.brainbox.api.traditional.web.TraditionalStudentReportDto
import com.afrithecus.brainbox.api.traditional.web.ValidateAccessResponse
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

    private fun createExam(token: String, gradeLevel: String, subjects: List<SubjectConfigDto>, year: Int = 2026): TraditionalExamDto {
        val body = objectMapper.writeValueAsString(
            CreateTraditionalExamRequest(
                title = "End Term 2 " + year + " - " + gradeLevel,
                term = ExamTerm.TERM_2,
                gradeLevel = gradeLevel,
                year = year,
                subjects = subjects,
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
        // Raw-total G.TOTAL band (140 -> BE under the default 120/200/280/350 bands),
        // matching the client's offline report fallback (overallBand(totalScore)).
        check(report.overallGrade == "BE")
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
        check(classes.first { it.name == "Grade 3A" }.allowed.not())
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
}
