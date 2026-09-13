package com.afrithecus.brainbox.api.traditional

import com.afrithecus.brainbox.api.auth.web.AuthResponse
import com.afrithecus.brainbox.api.identity.entity.SchoolEntity
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.model.Role
import com.afrithecus.brainbox.api.identity.model.SubRole
import com.afrithecus.brainbox.api.identity.repository.SchoolRepository
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import com.afrithecus.brainbox.api.notification.web.AppNotificationPayload
import com.afrithecus.brainbox.api.traditional.model.ExamTerm
import com.afrithecus.brainbox.api.traditional.model.TraditionalEditStatus
import com.afrithecus.brainbox.api.traditional.model.TraditionalExamStatus
import com.afrithecus.brainbox.api.traditional.model.TraditionalSubjectType
import com.afrithecus.brainbox.api.traditional.web.CreateTraditionalExamRequest
import com.afrithecus.brainbox.api.traditional.web.EditRequestDto
import com.afrithecus.brainbox.api.traditional.web.ExamConfirmationStatusDto
import com.afrithecus.brainbox.api.traditional.web.GradingBandDto
import com.afrithecus.brainbox.api.traditional.web.GradingConfigDto
import com.afrithecus.brainbox.api.traditional.web.MarkEntryDto
import com.afrithecus.brainbox.api.traditional.web.PreFinalCheckSummaryDto
import com.afrithecus.brainbox.api.traditional.web.StudentGradeRowDto
import com.afrithecus.brainbox.api.traditional.web.SubjectComponentDto
import com.afrithecus.brainbox.api.traditional.web.SubjectConfigDto
import com.afrithecus.brainbox.api.traditional.web.TraditionalExamAnalyticsDto
import com.afrithecus.brainbox.api.traditional.web.TraditionalExamDto
import com.afrithecus.brainbox.api.traditional.web.TraditionalMarkDto
import com.afrithecus.brainbox.api.traditional.web.TraditionalStudentReportDto
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
    fun `grade config round-trips, generation is idempotent and self-approval is rejected`() {
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
        mockMvc.perform(
            post("/traditional/edit-requests/${created.id}/approve").header("Authorization", auth(token))
        ).andExpect(status().isForbidden)
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
        val teacher = user(Role.TEACHER, "Teacher Edit", "0700000501", grade = "Grade 8")
        val student = user(Role.STUDENT, "Student Edit", "0700000502", grade = "Grade 8")
        val coordinatorToken = token(coordinator)
        val teacherToken = token(teacher)

        val exam = createExam(coordinatorToken, "Grade 8", listOf(SubjectConfigDto("MAT", "Mathematics", 100, TraditionalSubjectType.SINGLE)))
        mockMvc.perform(
            post("/traditional/exams/${exam.examId}/marks").header("Authorization", auth(teacherToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(listOf(MarkEntryDto(student.id.toString(), "MAT", 50))))
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
}
