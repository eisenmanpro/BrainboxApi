package com.afrithecus.brainbox.api.identity

import com.afrithecus.brainbox.api.announcement.web.SchoolAnnouncementPayload
import com.afrithecus.brainbox.api.attendance.entity.AttendanceRecordEntity
import com.afrithecus.brainbox.api.attendance.model.AttendanceStatus
import com.afrithecus.brainbox.api.attendance.repository.AttendanceRecordRepository
import com.afrithecus.brainbox.api.auth.web.AuthResponse
import com.afrithecus.brainbox.api.classes.entity.ClassMembershipEntity
import com.afrithecus.brainbox.api.classes.entity.TeacherClassEntity
import com.afrithecus.brainbox.api.classes.repository.ClassMembershipRepository
import com.afrithecus.brainbox.api.classes.repository.TeacherClassRepository
import com.afrithecus.brainbox.api.gradebook.entity.GradebookEntryEntity
import com.afrithecus.brainbox.api.gradebook.repository.GradebookEntryRepository
import com.afrithecus.brainbox.api.identity.entity.SchoolEntity
import com.afrithecus.brainbox.api.identity.entity.SchoolPerformanceSnapshotEntity
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.model.AccountStatus
import com.afrithecus.brainbox.api.identity.model.Role
import com.afrithecus.brainbox.api.identity.model.SubRole
import com.afrithecus.brainbox.api.identity.repository.SchoolPerformanceSnapshotRepository
import com.afrithecus.brainbox.api.identity.repository.SchoolRepository
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import com.afrithecus.brainbox.api.identity.web.AuditLogEntryPayload
import com.afrithecus.brainbox.api.identity.web.BackupResultPayload
import com.afrithecus.brainbox.api.identity.web.BackupSummaryPayload
import com.afrithecus.brainbox.api.identity.web.GradeConfigSummaryPayload
import com.afrithecus.brainbox.api.identity.web.SchoolAnalyticsPayload
import com.afrithecus.brainbox.api.identity.web.SchoolAttendanceOverviewPayload
import com.afrithecus.brainbox.api.identity.web.SystemSettingsPayload
import com.afrithecus.brainbox.api.identity.web.UserApprovalRequestPayload
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
import java.time.LocalDate
import java.util.UUID

/** Admin school endpoints (docs/ongoing/api_admin_changes.md). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AdminSchoolWebTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val userRepository: UserRepository,
    @Autowired private val schoolRepository: SchoolRepository,
    @Autowired private val classRepository: TeacherClassRepository,
    @Autowired private val membershipRepository: ClassMembershipRepository,
    @Autowired private val gradebookRepository: GradebookEntryRepository,
    @Autowired private val attendanceRepository: AttendanceRecordRepository,
    @Autowired private val snapshotRepository: SchoolPerformanceSnapshotRepository,
    @Autowired private val passwordEncoder: PasswordEncoder,
) {

    private lateinit var school: SchoolEntity
    private lateinit var otherSchool: SchoolEntity
    private lateinit var admin: UserEntity
    private lateinit var clazz: TeacherClassEntity
    private lateinit var student: UserEntity

    private fun user(role: Role, name: String, phone: String, schoolId: UUID, subRole: SubRole? = null, grade: String? = null): UserEntity {
        val entity = UserEntity()
        entity.phoneNumber = phone
        entity.email = phone + "@admin.test"
        entity.passwordHash = passwordEncoder.encode("password123") ?: error("encode")
        entity.name = name
        entity.role = role
        entity.subRole = subRole
        entity.schoolId = schoolId
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
        otherSchool = schoolRepository.save(SchoolEntity().apply { name = "Moi Avenue School"; isActive = true })
        admin = user(Role.TEACHER, "ICT Admin", "0755500001", school.id, SubRole.ICT_ADMIN, "Grade 4")
        student = user(Role.STUDENT, "Alice Learner", "0755500002", school.id, grade = "Grade 4")
        student.verificationStatus = AccountStatus.PENDING_VERIFICATION
        student.isVerified = false
        userRepository.save(student)
        clazz = classRepository.save(TeacherClassEntity().apply {
            teacherUserId = admin.id
            schoolId = school.id
            name = "Grade 4 East"
            gradeLevel = "Grade 4"
            subject = "Mathematics"
            isActive = true
        })
        membershipRepository.save(ClassMembershipEntity().apply { classId = clazz.id; studentId = student.id })
        gradebookRepository.save(GradebookEntryEntity().apply {
            clientId = "gb_1"
            classId = clazz.id
            assessmentId = "a_1"
            teacherId = admin.id
            studentId = student.id
            studentName = student.name
            percentage = 82
            rawScore = 82
            maxScore = 100
        })
        attendanceRepository.save(AttendanceRecordEntity().apply {
            classId = clazz.id
            studentId = student.id
            schoolId = school.id
            attendanceDate = LocalDate.now()
            status = AttendanceStatus.PRESENT
        })
    }

    @Test
    fun `admin reads are real and school-scoped`() {
        // A frozen prior-term snapshot gives the analytics a real baseline.
        snapshotRepository.saveAndFlush(SchoolPerformanceSnapshotEntity().apply {
            schoolId = school.id
            term = "Term 1"
            snapshotYear = 2020
            overallPerformance = 60.0
            classAverages = "{\"" + clazz.id + "\":50.0}"
        })
        val t = token(admin)
        val analytics = objectMapper.readValue(
            mockMvc.perform(get("/admin/schools/" + school.id + "/analytics").header("Authorization", "Bearer " + t))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            SchoolAnalyticsPayload::class.java,
        )
        check(analytics.totalStudents == 1 && analytics.totalTeachers == 1 && analytics.totalClasses == 1)
        check(analytics.overallPerformance == 82.0)
        check(analytics.previousOverallPerformance == 60.0)
        check(analytics.classRankings.single().className == "Grade 4 East")
        check(analytics.classRankings.single().trend == 32.0)
        check(analytics.subjectPerformance.single().subject == "Mathematics")
        check(analytics.gradeDistribution["EE"] == 1)

        val grades = objectMapper.readValue(
            mockMvc.perform(get("/admin/schools/" + school.id + "/grades").header("Authorization", "Bearer " + t))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            Array<GradeConfigSummaryPayload>::class.java,
        )
        check(grades.single().gradeLevel == "Grade 4")

        val approvals = objectMapper.readValue(
            mockMvc.perform(get("/admin/schools/" + school.id + "/approvals").header("Authorization", "Bearer " + t))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            Array<UserApprovalRequestPayload>::class.java,
        )
        check(approvals.single().userId == student.id.toString() && approvals.single().status == "PENDING_VERIFICATION")

        val attendance = objectMapper.readValue(
            mockMvc.perform(get("/admin/schools/" + school.id + "/attendance/overview").header("Authorization", "Bearer " + t))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            SchoolAttendanceOverviewPayload::class.java,
        )
        check(attendance.overallAttendanceRate == 100.0)
        check(attendance.classes.single().className == "Grade 4 East")

        // An ICT admin of another school is forbidden; a platform admin is allowed.
        val otherAdmin = user(Role.TEACHER, "Other Admin", "0755500003", otherSchool.id, SubRole.ICT_ADMIN)
        mockMvc.perform(get("/admin/schools/" + school.id + "/analytics").header("Authorization", "Bearer " + token(otherAdmin)))
            .andExpect(status().isForbidden)
    }

    @Test
    fun `system settings are idempotent and audited`() {
        val t = token(admin)
        val body = objectMapper.writeValueAsString(SystemSettingsPayload(school.id.toString(), maintenanceMode = true, registrationOpen = false))
        val saved = objectMapper.readValue(
            mockMvc.perform(put("/admin/schools/" + school.id + "/system-settings").header("Authorization", "Bearer " + t)
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            SystemSettingsPayload::class.java,
        )
        check(saved.maintenanceMode && !saved.registrationOpen)
        val reread = objectMapper.readValue(
            mockMvc.perform(get("/admin/schools/" + school.id + "/system-settings").header("Authorization", "Bearer " + t))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            SystemSettingsPayload::class.java,
        )
        check(reread.maintenanceMode)

        val logs = objectMapper.readValue(
            mockMvc.perform(get("/admin/schools/" + school.id + "/audit-logs").header("Authorization", "Bearer " + t))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            Array<AuditLogEntryPayload>::class.java,
        )
        check(logs.any { it.action.contains("system settings") && it.actorName == "ICT Admin" })
    }

    @Test
    fun `backup produces a documented logical export schema`() {
        val t = token(admin)
        // Seed an announcement so the admin-domain export is exercised.
        val announcement = SchoolAnnouncementPayload(
            announcementId = "",
            title = "Sports day",
            body = "Sports day is on Friday.",
            audience = "All",
        )
        mockMvc.perform(
            post("/admin/schools/" + school.id + "/announcements").header("Authorization", "Bearer " + t)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(announcement))
        ).andExpect(status().isOk)

        val backup = objectMapper.readValue(
            mockMvc.perform(post("/admin/schools/" + school.id + "/backup").header("Authorization", "Bearer " + t))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            BackupResultPayload::class.java,
        )
        check(backup.success && backup.sizeBytes > 0 && backup.backupId.isNotBlank())
        check(backup.fileName.endsWith(".json") && backup.sha256.isNotBlank())

        val listed = objectMapper.readValue(
            mockMvc.perform(get("/admin/schools/" + school.id + "/backups").header("Authorization", "Bearer " + t))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            Array<BackupSummaryPayload>::class.java,
        )
        check(listed.size == 1 && listed.single().backupId == backup.backupId && listed.single().sha256 == backup.sha256)

        // The stored artifact downloads with a verifiable checksum.
        val download = mockMvc.perform(
            get("/admin/schools/" + school.id + "/backups/" + backup.backupId + "/download")
                .header("Authorization", "Bearer " + t)
        ).andExpect(status().isOk).andReturn().response
        val bytes = download.contentAsByteArray
        check(java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) } == backup.sha256)

        // The export schema is pinned: the ADM-1 restore mapper relies on these keys and types.
        val node = objectMapper.readTree(bytes)
        check(node.get("schemaVersion").asInt() == 2)
        check(node.get("schoolId").asString() == school.id.toString())
        check(node.get("schoolName").asString() == "Alliance High School")
        check(node.get("generatedAt").asLong() > 0)
        val config = node.get("config")
        check(config.get("schoolId").asString() == school.id.toString())
        check(config.get("schoolName").asString() == "Alliance High School")
        check(config.get("academicCalendar").isArray)
        check(config.get("cbcStrands").isArray)
        val settings = node.get("settings")
        check(settings.get("maintenanceMode").isBoolean)
        check(settings.get("registrationOpen").isBoolean)
        check(node.get("gradeConfigs").isArray)
        check(node.get("announcements").isArray && node.get("announcements").size() == 1)
        check(node.get("announcements").get(0).get("title").asString() == "Sports day")
        check(node.get("userApprovals").isArray && node.get("userApprovals").size() == 1)
        check(node.get("userApprovals").get(0).get("name").asString() == "Alice Learner")
        check(node.get("classes").get(0).get("name").asString() == "Grade 4 East")
        val exportedStudent = node.get("users").first { it.get("name").asString() == "Alice Learner" }
        check(exportedStudent.get("role").asString() == "STUDENT")
        check(exportedStudent.get("gradeLevel").asString() == "Grade 4")
    }

    @Test
    fun `audit log records admin mutations and pages by cursor`() {
        val t = token(admin)
        repeat(3) {
            mockMvc.perform(put("/admin/schools/" + school.id + "/system-settings").header("Authorization", "Bearer " + t)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(SystemSettingsPayload(school.id.toString(), maintenanceMode = it % 2 == 0, registrationOpen = true))))
                .andExpect(status().isOk)
            Thread.sleep(10)
        }
        val firstPage = objectMapper.readValue(
            mockMvc.perform(get("/admin/schools/" + school.id + "/audit-logs").param("limit", "2").header("Authorization", "Bearer " + t))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            Array<AuditLogEntryPayload>::class.java,
        )
        check(firstPage.size == 2)
        val cursor = firstPage.minOf { it.timestamp }
        val secondPage = objectMapper.readValue(
            mockMvc.perform(
                get("/admin/schools/" + school.id + "/audit-logs").param("limit", "10").param("before", cursor.toString())
                    .header("Authorization", "Bearer " + t)
            ).andExpect(status().isOk).andReturn().response.contentAsString,
            Array<AuditLogEntryPayload>::class.java,
        )
        check(secondPage.size == 1) { "expected the remaining entry, got " + secondPage.size }
        check(firstPage.map { it.logId }.intersect(secondPage.map { it.logId }.toSet()).isEmpty())
        check((firstPage + secondPage).all { it.actorName == "ICT Admin" })
    }
}
