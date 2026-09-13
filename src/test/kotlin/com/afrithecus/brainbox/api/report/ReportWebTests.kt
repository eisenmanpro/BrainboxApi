package com.afrithecus.brainbox.api.report

import com.afrithecus.brainbox.api.auth.web.AuthResponse
import com.afrithecus.brainbox.api.identity.entity.SchoolEntity
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.model.Role
import com.afrithecus.brainbox.api.identity.model.SubRole
import com.afrithecus.brainbox.api.identity.repository.SchoolRepository
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import com.afrithecus.brainbox.api.identity.web.SchoolConfigPayload
import com.afrithecus.brainbox.api.report.entity.ReportJobEntity
import com.afrithecus.brainbox.api.report.repository.ReportJobRepository
import com.afrithecus.brainbox.api.report.web.ReportGenerationRequestPayload
import com.afrithecus.brainbox.api.report.web.ReportHistoryItemPayload
import com.afrithecus.brainbox.api.report.web.ReportJobPayload
import com.afrithecus.brainbox.api.report.web.ReportJobStatus
import com.afrithecus.brainbox.api.report.web.ReportQuotaPayload
import com.afrithecus.brainbox.api.report.web.ReportSchedulePayload
import com.afrithecus.brainbox.api.report.web.ReportType
import com.afrithecus.brainbox.api.traditional.model.ExamTerm
import com.afrithecus.brainbox.api.traditional.model.TraditionalSubjectType
import com.afrithecus.brainbox.api.traditional.web.CreateTraditionalExamRequest
import com.afrithecus.brainbox.api.traditional.web.MarkEntryDto
import com.afrithecus.brainbox.api.traditional.web.SubjectConfigDto
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.util.UUID

/** Reporting hub end to end (docs/ongoing/api_reports_changes.md). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ReportWebTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val userRepository: UserRepository,
    @Autowired private val schoolRepository: SchoolRepository,
    @Autowired private val jobRepository: ReportJobRepository,
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
        admission: String? = null,
    ): UserEntity = userRepository.save(UserEntity().apply {
        phoneNumber = phone
        email = phone + "@report.test"
        passwordHash = passwordEncoder.encode("password123") ?: error("encode")
        this.name = name
        this.role = role
        this.subRole = subRole
        this@ReportWebTests.schoolId.let { this.schoolId = it }
        gradeLevel = grade
        studentAdmissionNumber = admission
        isVerified = true
        isActive = true
    })

    private fun token(entity: UserEntity): String {
        val login = mockMvc.perform(
            post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"identifier\":\"" + entity.email + "\",\"password\":\"password123\"}")
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return objectMapper.readValue(login, AuthResponse::class.java).sessionToken!!
    }

    private fun auth(token: String) = "Bearer " + token

    private fun createExam(token: String, gradeLevel: String): TraditionalExamDto {
        val body = objectMapper.writeValueAsString(
            CreateTraditionalExamRequest(
                title = "End Term 2 2026",
                term = ExamTerm.TERM_2,
                gradeLevel = gradeLevel,
                year = 2026,
                subjects = listOf(
                    SubjectConfigDto("MATH", "Mathematics", 100, TraditionalSubjectType.SINGLE),
                    SubjectConfigDto("ENG", "English", 100, TraditionalSubjectType.SINGLE),
                ),
            )
        )
        val response = mockMvc.perform(
            post("/traditional/exams").header("Authorization", auth(token))
                .contentType(MediaType.APPLICATION_JSON).content(body)
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return objectMapper.readValue(response, TraditionalExamDto::class.java)
    }

    private fun saveMarks(token: String, examId: String, entries: List<MarkEntryDto>) {
        val body = objectMapper.writeValueAsString(entries)
        mockMvc.perform(
            post("/traditional/exams/" + examId + "/marks").header("Authorization", auth(token))
                .contentType(MediaType.APPLICATION_JSON).content(body)
        ).andExpect(status().isOk)
    }

    private fun generate(token: String, request: ReportGenerationRequestPayload): ReportJobPayload {
        val body = objectMapper.writeValueAsString(request)
        val response = mockMvc.perform(
            post("/teacher/reports/generate").header("Authorization", auth(token))
                .contentType(MediaType.APPLICATION_JSON).content(body)
        ).andReturn().response
        check(response.status == 200) { "generate failed: " + response.status + " " + response.contentAsString }
        return objectMapper.readValue(response.contentAsString, ReportJobPayload::class.java)
    }

    private fun examRequest(examId: String, jobRequestId: String): ReportGenerationRequestPayload =
        ReportGenerationRequestPayload(
            reportType = ReportType.TRADITIONAL_COMBINED,
            term = "Term 2",
            year = 2026,
            examId = examId,
            jobRequestId = jobRequestId,
            schoolId = schoolId.toString(),
        )

    @Test
    fun `generate is idempotent, branded and quota-limited`() {
        val coordinator = user(Role.TEACHER, "Coordinator", "0755121001", grade = "Grade 4", subRole = SubRole.GRADE_COORDINATOR)
        val alice = user(Role.STUDENT, "Alice Mwangi", "0755121002", grade = "Grade 4", admission = "ADM-001")
        val bob = user(Role.STUDENT, "Bob Otieno", "0755121003", grade = "Grade 4", admission = "ADM-002")
        val t = token(coordinator)
        val exam = createExam(t, "Grade 4")
        saveMarks(t, exam.examId, listOf(
            MarkEntryDto(alice.id.toString(), "MATH", 80),
            MarkEntryDto(alice.id.toString(), "ENG", 60),
            MarkEntryDto(bob.id.toString(), "MATH", 70),
            MarkEntryDto(bob.id.toString(), "ENG", 90),
        ))

        val first = generate(t, examRequest(exam.examId, "job-1"))
        check(first.status == ReportJobStatus.READY) { "expected READY, got " + first.status + " " + first.message }
        check(first.fileUrl != null)
        check(first.fileName!!.endsWith(".pdf"))
        check(first.fileSize > 0)
        val replay = generate(t, examRequest(exam.examId, "job-1"))
        check(replay.jobId == first.jobId) { "idempotency key must return the same job" }

        val polled = objectMapper.readValue(
            mockMvc.perform(get("/teacher/reports/job/" + first.jobId).header("Authorization", auth(t)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            ReportJobPayload::class.java,
        )
        check(polled.status == ReportJobStatus.READY)

        val history = objectMapper.readValue(
            mockMvc.perform(get("/teacher/reports/history").param("limit", "50").header("Authorization", auth(t)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            Array<ReportHistoryItemPayload>::class.java,
        )
        check(history.any { it.id == first.jobId && it.fileUrl.isNotBlank() && it.status == ReportJobStatus.READY })

        val quota = objectMapper.readValue(
            mockMvc.perform(get("/teacher/reports/quota").header("Authorization", auth(t)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            ReportQuotaPayload::class.java,
        )
        check(quota.limit == 3 && quota.used == 0 && quota.remaining == 3)

        // The signed URL downloads without a bearer header and is counted against quota.
        val uri = URI(first.fileUrl)
        val download = mockMvc.perform(get(uri.rawPath + "?" + uri.rawQuery))
            .andExpect(status().isOk).andReturn().response
        check(download.contentType == "application/pdf")
        val bytes = download.contentAsByteArray
        check(bytes.size > 500 && String(bytes, 0, 5, Charsets.ISO_8859_1) == "%PDF-")

        repeat(2) { mockMvc.perform(get(uri.rawPath + "?" + uri.rawQuery)).andExpect(status().isOk) }
        val exhausted = objectMapper.readValue(
            mockMvc.perform(get("/teacher/reports/quota").header("Authorization", auth(t)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            ReportQuotaPayload::class.java,
        )
        check(exhausted.remaining == 0) { "quota should be exhausted after 3 exports" }
        mockMvc.perform(get(uri.rawPath + "?" + uri.rawQuery)).andExpect(status().isTooManyRequests)

        // A tampered token is rejected.
        mockMvc.perform(get(uri.rawPath).param("token", "not-a-token")).andExpect(status().isForbidden)
    }

    @Test
    fun `generate requires a coordinator and ignores body identity`() {
        val plainTeacher = user(Role.TEACHER, "Plain Teacher", "0755121004", grade = "Grade 4")
        val student = user(Role.STUDENT, "Learner", "0755121005", grade = "Grade 4")
        val request = objectMapper.writeValueAsString(
            ReportGenerationRequestPayload(
                reportType = ReportType.CBC_CLASS,
                classId = UUID.randomUUID().toString(),
                term = "Term 2",
                jobRequestId = "job-x",
                teacherId = student.id.toString(),
                schoolId = schoolId.toString(),
            )
        )
        mockMvc.perform(
            post("/teacher/reports/generate").header("Authorization", auth(token(plainTeacher)))
                .contentType(MediaType.APPLICATION_JSON).content(request)
        ).andExpect(status().isForbidden)
        mockMvc.perform(
            post("/teacher/reports/generate").header("Authorization", auth(token(student)))
                .contentType(MediaType.APPLICATION_JSON).content(request)
        ).andExpect(status().isForbidden)
    }

    @Test
    fun `cancelling a queued job is repeat-safe`() {
        val coordinator = user(Role.TEACHER, "Coordinator", "0755121006", grade = "Grade 4", subRole = SubRole.GRADE_COORDINATOR)
        val t = token(coordinator)
        val job = jobRepository.saveAndFlush(ReportJobEntity().apply {
            ownerId = coordinator.id
            requestId = "cancel-1"
            schoolId = schoolId
            reportType = ReportType.CBC_CLASS.name
            title = "Class CBC Report"
            status = "QUEUED"
        })
        val cancelled = objectMapper.readValue(
            mockMvc.perform(post("/teacher/reports/job/" + job.id + "/cancel").header("Authorization", auth(t)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            ReportJobPayload::class.java,
        )
        check(cancelled.status == ReportJobStatus.FAILED && cancelled.message == "Cancelled")
        mockMvc.perform(post("/teacher/reports/job/" + job.id + "/cancel").header("Authorization", auth(t)))
            .andExpect(status().isOk)
    }

    @Test
    fun `history pages by before cursor`() {
        val coordinator = user(Role.TEACHER, "Coordinator", "0755121007", grade = "Grade 4", subRole = SubRole.GRADE_COORDINATOR)
        val alice = user(Role.STUDENT, "Alice", "0755121008", grade = "Grade 4", admission = "ADM-003")
        val t = token(coordinator)
        val exam = createExam(t, "Grade 4")
        saveMarks(t, exam.examId, listOf(MarkEntryDto(alice.id.toString(), "MATH", 80)))
        repeat(3) { index ->
            generate(t, examRequest(exam.examId, "page-" + index))
            Thread.sleep(15)
        }
        val firstPage = objectMapper.readValue(
            mockMvc.perform(get("/teacher/reports/history").param("limit", "2").header("Authorization", auth(t)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            Array<ReportHistoryItemPayload>::class.java,
        )
        check(firstPage.size == 2)
        val cursor = firstPage.minOf { it.generatedAt }
        val secondPage = objectMapper.readValue(
            mockMvc.perform(
                get("/teacher/reports/history").param("limit", "2").param("before", cursor.toString())
                    .header("Authorization", auth(t))
            ).andExpect(status().isOk).andReturn().response.contentAsString,
            Array<ReportHistoryItemPayload>::class.java,
        )
        check(secondPage.size == 1) { "older page should hold the remaining job, got " + secondPage.size }
        check((firstPage.map { it.id } + secondPage.map { it.id }).toSet().size == 3)
    }

    @Test
    fun `schedules are server-authoritative, idempotent and scoped`() {
        val coordinator = user(Role.TEACHER, "Coordinator", "0755121009", grade = "Grade 4", subRole = SubRole.GRADE_COORDINATOR)
        val other = user(Role.TEACHER, "Other Coordinator", "0755121010", grade = "Grade 4", subRole = SubRole.GRADE_COORDINATOR)
        val t = token(coordinator)
        val otherToken = token(other)
        val body = objectMapper.writeValueAsString(
            ReportSchedulePayload(
                id = "s_local_1",
                title = "Termly Class CBC",
                reportType = ReportType.CBC_CLASS,
                classId = UUID.randomUUID().toString(),
                term = "Term 2",
                frequency = "WEEKLY",
                destination = "Device",
                enabled = true,
                nextRunAt = 0,
                createdAt = 0,
            )
        )
        val created = objectMapper.readValue(
            mockMvc.perform(
                post("/teacher/reports/schedules").header("Authorization", auth(t))
                    .contentType(MediaType.APPLICATION_JSON).content(body)
            ).andExpect(status().isOk).andReturn().response.contentAsString,
            ReportSchedulePayload::class.java,
        )
        check(created.nextRunAt > 0) { "the server must stamp the next run" }
        val replay = objectMapper.readValue(
            mockMvc.perform(
                post("/teacher/reports/schedules").header("Authorization", auth(t))
                    .contentType(MediaType.APPLICATION_JSON).content(body)
            ).andExpect(status().isOk).andReturn().response.contentAsString,
            ReportSchedulePayload::class.java,
        )
        check(replay.id == created.id) { "replayed create must not duplicate" }
        mockMvc.perform(get("/teacher/reports/schedules").header("Authorization", auth(t)))
            .andExpect(status().isOk)

        val toggled = objectMapper.readValue(
            mockMvc.perform(
                put("/teacher/reports/schedules/" + created.id).header("Authorization", auth(t))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body.replace("\"enabled\":true", "\"enabled\":false"))
            ).andExpect(status().isOk).andReturn().response.contentAsString,
            ReportSchedulePayload::class.java,
        )
        check(!toggled.enabled)

        mockMvc.perform(get("/teacher/reports/schedules").header("Authorization", auth(otherToken)))
            .andExpect(status().isOk)
        mockMvc.perform(delete("/teacher/reports/schedules/" + created.id).header("Authorization", auth(t)))
            .andExpect(status().isNoContent)
        mockMvc.perform(delete("/teacher/reports/schedules/" + created.id).header("Authorization", auth(t)))
            .andExpect(status().isNoContent)
    }

    @Test
    fun `admin manages school branding used by reports`() {
        val admin = user(Role.ADMIN, "Admin", "0755121011")
        val coordinator = user(Role.TEACHER, "Coordinator", "0755121012", grade = "Grade 4", subRole = SubRole.GRADE_COORDINATOR)
        val payload = objectMapper.writeValueAsString(
            SchoolConfigPayload(
                schoolId = schoolId.toString(),
                schoolName = "Alliance High School",
                motto = "Knowledge is Power",
                primaryColor = "#1F2A44",
                watermarkText = "Alliance",
                academicCalendar = listOf("Term 1", "Term 2"),
                cbcStrands = listOf("ENG"),
            )
        )
        val saved = objectMapper.readValue(
            mockMvc.perform(
                put("/admin/schools/" + schoolId + "/config").header("Authorization", auth(token(admin)))
                    .contentType(MediaType.APPLICATION_JSON).content(payload)
            ).andExpect(status().isOk).andReturn().response.contentAsString,
            SchoolConfigPayload::class.java,
        )
        check(saved.motto == "Knowledge is Power" && saved.academicCalendar.size == 2)
        val reread = objectMapper.readValue(
            mockMvc.perform(get("/admin/schools/" + schoolId + "/config").header("Authorization", auth(token(admin))))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            SchoolConfigPayload::class.java,
        )
        check(reread.watermarkText == "Alliance")
        mockMvc.perform(
            put("/admin/schools/" + schoolId + "/config").header("Authorization", auth(token(coordinator)))
                .contentType(MediaType.APPLICATION_JSON).content(payload)
        ).andExpect(status().isForbidden)
    }
}
