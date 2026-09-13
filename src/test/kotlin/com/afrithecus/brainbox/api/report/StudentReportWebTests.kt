package com.afrithecus.brainbox.api.report

import com.afrithecus.brainbox.api.auth.web.AuthResponse
import com.afrithecus.brainbox.api.classes.entity.ClassMembershipEntity
import com.afrithecus.brainbox.api.classes.entity.TeacherClassEntity
import com.afrithecus.brainbox.api.classes.repository.ClassMembershipRepository
import com.afrithecus.brainbox.api.classes.repository.TeacherClassRepository
import com.afrithecus.brainbox.api.identity.entity.SchoolEntity
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.model.Role
import com.afrithecus.brainbox.api.identity.repository.SchoolRepository
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import com.afrithecus.brainbox.api.report.web.ReportJobPayload
import com.afrithecus.brainbox.api.report.web.ReportJobStatus
import com.afrithecus.brainbox.api.report.web.ReportQuotaPayload
import com.afrithecus.brainbox.api.report.web.ReportType
import com.afrithecus.brainbox.api.report.web.StudentReportRequestPayload
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
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
import java.net.URI

/** Learner/parent report exports (docs/ongoing/pdf_generator_cleanup.md section 3). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class StudentReportWebTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val userRepository: UserRepository,
    @Autowired private val schoolRepository: SchoolRepository,
    @Autowired private val classRepository: TeacherClassRepository,
    @Autowired private val membershipRepository: ClassMembershipRepository,
    @Autowired private val passwordEncoder: PasswordEncoder,
) {

    private lateinit var school: SchoolEntity
    private lateinit var teacher: UserEntity
    private lateinit var student: UserEntity
    private lateinit var otherStudent: UserEntity
    private lateinit var parent: UserEntity

    @BeforeEach
    fun setUp() {
        school = schoolRepository.save(SchoolEntity().apply { name = "Alliance High School"; isActive = true })
        teacher = user(Role.TEACHER, "Teacher One", "0755900001", grade = "Grade 4")
        student = user(Role.STUDENT, "Alice Learner", "0755900002", grade = "Grade 4")
        otherStudent = user(Role.STUDENT, "Bob Learner", "0755900003", grade = "Grade 5")
        parent = user(Role.PARENT, "Parent One", "0755900004")
        student.parentUserId = parent.id
        userRepository.save(student)
        val clazz = classRepository.save(TeacherClassEntity().apply {
            teacherUserId = teacher.id
            schoolId = school.id
            name = "Grade 4 East"
            gradeLevel = "Grade 4"
            subject = "Mathematics"
            isActive = true
        })
        membershipRepository.save(ClassMembershipEntity().apply { classId = clazz.id; studentId = student.id })
    }

    private fun user(role: Role, name: String, phone: String, grade: String? = null): UserEntity =
        userRepository.save(UserEntity().apply {
            phoneNumber = phone
            email = phone + "@studentreport.test"
            passwordHash = passwordEncoder.encode("password123") ?: error("encode")
            this.name = name
            this.role = role
            this@StudentReportWebTests.school.id.let { this.schoolId = it }
            gradeLevel = grade
            isVerified = true
            isActive = true
        })

    private fun token(entity: UserEntity): String {
        val body = mockMvc.perform(
            post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"identifier\":\"" + entity.email + "\",\"password\":\"password123\"}")
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return objectMapper.readValue(body, AuthResponse::class.java).sessionToken!!
    }

    private fun auth(token: String) = "Bearer " + token

    private fun generate(token: String, request: StudentReportRequestPayload): ReportJobPayload {
        val response = mockMvc.perform(
            post("/student/reports/generate").header("Authorization", auth(token))
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(request))
        ).andReturn().response
        check(response.status == 200) { "generate failed: " + response.status + " " + response.contentAsString }
        return objectMapper.readValue(response.contentAsString, ReportJobPayload::class.java)
    }

    private fun download(job: ReportJobPayload): ByteArray {
        val uri = URI(job.fileUrl)
        return mockMvc.perform(get(uri.rawPath + "?" + uri.rawQuery))
            .andExpect(status().isOk).andReturn().response.contentAsByteArray
    }

    @Test
    fun `learner generates and downloads their own cbc report`() {
        val t = token(student)
        val job = generate(t, StudentReportRequestPayload(reportType = ReportType.CBC_STUDENT, term = "Term 2"))
        check(job.status == ReportJobStatus.READY) { "expected READY, got " + job.status + " " + job.message }
        check(job.fileUrl != null && job.fileName!!.endsWith(".pdf") && job.fileSize > 0)
        val bytes = download(job)
        check(bytes.size > 500 && String(bytes, 0, 5, Charsets.ISO_8859_1) == "%PDF-")
        val polled = objectMapper.readValue(
            mockMvc.perform(get("/student/reports/job/" + job.jobId).header("Authorization", auth(t)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            ReportJobPayload::class.java,
        )
        check(polled.status == ReportJobStatus.READY)
        val quota = objectMapper.readValue(
            mockMvc.perform(get("/student/reports/quota").header("Authorization", auth(t)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            ReportQuotaPayload::class.java,
        )
        check(quota.limit > 0)
    }

    @Test
    fun `detailed cbc report carries the analytics page`() {
        val job = generate(token(student), StudentReportRequestPayload(reportType = ReportType.DETAILED_CBC_STUDENT, term = "Term 2"))
        val text = Loader.loadPDF(download(job)).use { PDFTextStripper().getText(it) }
        check(text.contains("CBC Analytics Report")) { "detailed page missing" }
        check(text.contains("Attendance & Engagement"))
        check(text.contains("CBC Assessment Levels"))
    }

    @Test
    fun `parent generates a report for a linked child`() {
        val job = generate(token(parent), StudentReportRequestPayload(reportType = ReportType.CBC_STUDENT, studentId = student.id.toString(), term = "Term 2"))
        check(job.status == ReportJobStatus.READY)
    }

    @Test
    fun `teacher of record generates a learner report`() {
        val job = generate(token(teacher), StudentReportRequestPayload(reportType = ReportType.CBC_STUDENT, studentId = student.id.toString(), term = "Term 2"))
        check(job.status == ReportJobStatus.READY)
    }

    @Test
    fun `reports are scoped to self linked child and teacher of record`() {
        val learner = token(student)
        fun body(studentId: String?, type: ReportType = ReportType.CBC_STUDENT) =
            objectMapper.writeValueAsString(StudentReportRequestPayload(reportType = type, studentId = studentId))

        mockMvc.perform(
            post("/student/reports/generate").header("Authorization", auth(learner))
                .contentType(MediaType.APPLICATION_JSON).content(body(otherStudent.id.toString()))
        ).andExpect(status().isForbidden)
        mockMvc.perform(
            post("/student/reports/generate").header("Authorization", auth(token(parent)))
                .contentType(MediaType.APPLICATION_JSON).content(body(otherStudent.id.toString()))
        ).andExpect(status().isForbidden)
        mockMvc.perform(
            post("/student/reports/generate").header("Authorization", auth(token(teacher)))
                .contentType(MediaType.APPLICATION_JSON).content(body(otherStudent.id.toString()))
        ).andExpect(status().isForbidden)
        mockMvc.perform(
            post("/student/reports/generate").header("Authorization", auth(learner))
                .contentType(MediaType.APPLICATION_JSON).content(body(null, ReportType.TRADITIONAL_COMBINED))
        ).andExpect(status().isBadRequest)
        mockMvc.perform(
            post("/student/reports/generate").header("Authorization", auth(learner))
                .contentType(MediaType.APPLICATION_JSON).content(body(null, ReportType.TRADITIONAL_STUDENT))
        ).andExpect(status().isBadRequest)
        mockMvc.perform(
            post("/student/reports/generate").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(StudentReportRequestPayload(reportType = ReportType.CBC_STUDENT)))
        ).andExpect(status().isUnauthorized)
    }
}
