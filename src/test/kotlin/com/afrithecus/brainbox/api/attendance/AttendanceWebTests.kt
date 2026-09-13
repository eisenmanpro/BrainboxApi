package com.afrithecus.brainbox.api.attendance

import com.afrithecus.brainbox.api.attendance.entity.AttendanceRecordEntity
import com.afrithecus.brainbox.api.attendance.model.AttendanceStatus
import com.afrithecus.brainbox.api.attendance.repository.AttendanceRecordRepository
import com.afrithecus.brainbox.api.attendance.web.AttendanceAnalyticsPayload
import com.afrithecus.brainbox.api.attendance.web.AttendanceRecordPayload
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
import com.afrithecus.brainbox.api.attendance.web.AttendancePerformanceAnalyticsPayload
import com.afrithecus.brainbox.api.attendance.web.ChildAttendancePerformancePayload
import com.afrithecus.brainbox.api.attendance.web.ParentAttendanceRecordPayload
import com.afrithecus.brainbox.api.attendance.web.PdfResultPayload
import com.afrithecus.brainbox.api.exams.entity.ExamEntity
import com.afrithecus.brainbox.api.exams.entity.ExamSubmissionEntity
import com.afrithecus.brainbox.api.exams.repository.ExamRepository
import com.afrithecus.brainbox.api.exams.repository.ExamSubmissionRepository
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import com.afrithecus.brainbox.api.live.entity.LiveAttendanceEntity
import com.afrithecus.brainbox.api.live.entity.LiveClassEntity
import com.afrithecus.brainbox.api.live.repository.LiveAttendanceRepository
import com.afrithecus.brainbox.api.live.repository.LiveClassRepository
import com.afrithecus.brainbox.api.notification.web.AppNotificationPayload
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
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import java.util.UUID

/**
 * Teacher attendance register (doc 04 §4 / api_attendance_changes.md): idempotent
 * day-bucketed writes, mark-by-exception defensiveness, the CTEACHER role gate and
 * the parent absence fan-out.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AttendanceWebTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val userRepository: UserRepository,
    @Autowired private val schoolRepository: SchoolRepository,
    @Autowired private val classRepository: TeacherClassRepository,
    @Autowired private val membershipRepository: ClassMembershipRepository,
    @Autowired private val recordRepository: AttendanceRecordRepository,
    @Autowired private val liveClassRepository: LiveClassRepository,
    @Autowired private val liveAttendanceRepository: LiveAttendanceRepository,
    @Autowired private val examSubmissionRepository: ExamSubmissionRepository,
    @Autowired private val examRepository: ExamRepository,
    @Autowired private val passwordEncoder: PasswordEncoder,
) {

    private val zone: ZoneId = ZoneId.of("Africa/Nairobi")
    private lateinit var schoolId: UUID

    @BeforeEach
    fun setUp() {
        val school = SchoolEntity()
        school.name = "Alliance High School"
        school.isActive = true
        schoolRepository.save(school)
        schoolId = school.id
    }

    private fun user(role: Role, name: String, phone: String, subRole: SubRole? = null, parentId: UUID? = null): UserEntity {
        val entity = UserEntity()
        entity.phoneNumber = phone
        entity.email = phone + "@attendance.test"
        entity.passwordHash = passwordEncoder.encode("password123") ?: error("encode")
        entity.name = name
        entity.role = role
        entity.subRole = subRole
        entity.schoolId = schoolId
        entity.parentUserId = parentId
        entity.isVerified = true
        entity.isActive = true
        return userRepository.save(entity)
    }

    private fun teacherClass(owner: UserEntity, name: String): TeacherClassEntity {
        val clazz = TeacherClassEntity()
        clazz.teacherUserId = owner.id
        clazz.schoolId = schoolId
        clazz.name = name
        clazz.gradeLevel = "Grade 4"
        clazz.subject = "Mathematics"
        clazz.isActive = true
        return classRepository.save(clazz)
    }

    private fun enroll(clazz: TeacherClassEntity, student: UserEntity) {
        membershipRepository.save(ClassMembershipEntity().apply {
            classId = clazz.id
            studentId = student.id
        })
    }

    private fun token(user: UserEntity): String {
        val login = mockMvc.perform(
            post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"identifier\":\"${user.email}\",\"password\":\"password123\"}")
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return objectMapper.readValue(login, AuthResponse::class.java).sessionToken!!
    }

    private fun auth(token: String) = "Bearer " + token

    private fun midnightToday(): Long = LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()

    private fun record(clazz: TeacherClassEntity, student: UserEntity, status: String, date: Long, notes: String? = null): AttendanceRecordPayload =
        AttendanceRecordPayload(
            id = "att_" + clazz.id + "_" + student.id,
            classId = clazz.id.toString(),
            studentId = student.id.toString(),
            studentName = student.name,
            date = date,
            status = status,
            notes = notes,
        )

    @Test
    fun `register write is idempotent per day and fans an absence alert to the parent`() {
        val parent = user(Role.PARENT, "Parent One", "0722000100")
        val alice = user(Role.STUDENT, "Alice Mwangi", "0722000101", parentId = parent.id)
        val bob = user(Role.STUDENT, "Bob Otieno", "0722000102")
        val teacher = user(Role.TEACHER, "Class Teacher", "0722000103", subRole = SubRole.CTEACHER)
        val clazz = teacherClass(teacher, "Grade 4 South")
        enroll(clazz, alice)
        enroll(clazz, bob)

        val teacherToken = token(teacher)
        val parentToken = token(parent)
        val day = midnightToday()

        // Same local day, different time of day: both resolve to one register.
        val morning = day + 9 * 60 * 60 * 1000
        val body = objectMapper.writeValueAsString(listOf(record(clazz, alice, "ABSENT", morning, "Sick, parent called"), record(clazz, bob, "PRESENT", morning)))
        mockMvc.perform(
            post("/teacher/attendance").header("Authorization", auth(teacherToken))
                .contentType(MediaType.APPLICATION_JSON).content(body)
        ).andExpect(status().isOk)

        val read = objectMapper.readValue(
            mockMvc.perform(
                get("/teacher/classes/${clazz.id}/attendance").param("date", day.toString()).header("Authorization", auth(teacherToken))
            ).andExpect(status().isOk).andReturn().response.contentAsString,
            Array<AttendanceRecordPayload>::class.java,
        )
        check(read.size == 2)
        check(read.first { it.studentId == alice.id.toString() }.status == "ABSENT")
        check(read.first { it.studentId == alice.id.toString() }.notes == "Sick, parent called")
        check(read.first { it.studentId == bob.id.toString() }.status == "PRESENT")

        // Replay the register at a later hour: no duplicates and no duplicate alert.
        val afternoon = day + 15 * 60 * 60 * 1000
        val replay = objectMapper.writeValueAsString(listOf(record(clazz, alice, "ABSENT", afternoon), record(clazz, bob, "PRESENT", afternoon)))
        mockMvc.perform(
            post("/teacher/attendance").header("Authorization", auth(teacherToken))
                .contentType(MediaType.APPLICATION_JSON).content(replay)
        ).andExpect(status().isOk)
        check(recordRepository.findAllByClassIdAndAttendanceDate(clazz.id, LocalDate.now(zone)).size == 2)

        val alerts = objectMapper.readValue(
            mockMvc.perform(get("/notifications").param("userId", parent.id.toString()).header("Authorization", auth(parentToken)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            Array<AppNotificationPayload>::class.java,
        ).filter { it.type == "ATTENDANCE" }
        check(alerts.size == 1)
        check(alerts.single().actionRoute == "student_report/" + alice.id.toString())

        // Alice becomes PRESENT: the parent also gets the arrival alert (product decision).
        val corrected = objectMapper.writeValueAsString(listOf(record(clazz, alice, "PRESENT", day), record(clazz, bob, "PRESENT", day)))
        mockMvc.perform(
            post("/teacher/attendance").header("Authorization", auth(teacherToken))
                .contentType(MediaType.APPLICATION_JSON).content(corrected)
        ).andExpect(status().isOk)
        val afterCorrect = objectMapper.readValue(
            mockMvc.perform(get("/notifications").param("userId", parent.id.toString()).header("Authorization", auth(parentToken)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            Array<AppNotificationPayload>::class.java,
        ).filter { it.type == "ATTENDANCE" }
        check(afterCorrect.size == 2)
        check(afterCorrect.any { it.title.startsWith("Arrival Confirmed") && it.metadata["status"] == "PRESENT" })
    }

    @Test
    fun `a fresh present mark notifies the parent`() {
        val parent = user(Role.PARENT, "Parent Two", "0722000300")
        val child = user(Role.STUDENT, "Cara Learner", "0722000301", parentId = parent.id)
        val teacher = user(Role.TEACHER, "Present Teacher", "0722000302", subRole = SubRole.CTEACHER)
        val clazz = teacherClass(teacher, "Grade 5 South")
        enroll(clazz, child)
        val t = token(teacher)
        val day = midnightToday()
        mockMvc.perform(
            post("/teacher/attendance").header("Authorization", auth(t))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(listOf(record(clazz, child, "PRESENT", day))))
        ).andExpect(status().isOk)
        val alerts = objectMapper.readValue(
            mockMvc.perform(get("/notifications").param("userId", parent.id.toString()).header("Authorization", auth(token(parent))))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            Array<AppNotificationPayload>::class.java,
        ).filter { it.type == "ATTENDANCE" && it.metadata["status"] == "PRESENT" }
        check(alerts.size == 1) { "a fresh present mark should notify the parent" }
    }

    @Test
    fun `missing roster learners are marked absent and a plain teacher cannot file`() {
        val alice = user(Role.STUDENT, "Alice Mwangi", "0722000201")
        val bob = user(Role.STUDENT, "Bob Otieno", "0722000202")
        val classTeacher = user(Role.TEACHER, "Class Teacher", "0722000203", subRole = SubRole.CTEACHER)
        val plainTeacher = user(Role.TEACHER, "Subject Teacher", "0722000204")
        val clazz = teacherClass(classTeacher, "Grade 4 North")
        enroll(clazz, alice)
        enroll(clazz, bob)

        val classToken = token(classTeacher)
        val plainToken = token(plainTeacher)
        val day = midnightToday()

        // Only Alice posted: Bob (a roster member) is recorded ABSENT defensively.
        val body = objectMapper.writeValueAsString(listOf(record(clazz, alice, "PRESENT", day)))
        mockMvc.perform(
            post("/teacher/attendance").header("Authorization", auth(classToken))
                .contentType(MediaType.APPLICATION_JSON).content(body)
        ).andExpect(status().isOk)

        val read = objectMapper.readValue(
            mockMvc.perform(
                get("/teacher/classes/${clazz.id}/attendance").param("date", day.toString()).header("Authorization", auth(classToken))
            ).andExpect(status().isOk).andReturn().response.contentAsString,
            Array<AttendanceRecordPayload>::class.java,
        )
        check(read.first { it.studentId == bob.id.toString() }.status == "ABSENT")

        // A plain TEACHER owning the class still cannot file a register.
        val own = teacherClass(plainTeacher, "Grade 5 East")
        val plainBody = objectMapper.writeValueAsString(listOf(record(own, alice, "PRESENT", day)))
        mockMvc.perform(
            post("/teacher/attendance").header("Authorization", auth(plainToken))
                .contentType(MediaType.APPLICATION_JSON).content(plainBody)
        ).andExpect(status().isForbidden)
    }

    private fun seed(clazz: TeacherClassEntity, student: UserEntity, day: LocalDate, status: String) {
        recordRepository.save(AttendanceRecordEntity().apply {
            classId = clazz.id
            studentId = student.id
            schoolId = this@AttendanceWebTests.schoolId
            attendanceDate = day
            this.status = AttendanceStatus.valueOf(status)
        })
    }

    @Test
    fun `analytics flags chronic absence and keys the weekly heatmap Monday-first`() {
        val alice = user(Role.STUDENT, "Alice Mwangi", "0722000301")
        val bob = user(Role.STUDENT, "Bob Otieno", "0722000302")
        val teacher = user(Role.TEACHER, "Class Teacher", "0722000303", subRole = SubRole.CTEACHER)
        val clazz = teacherClass(teacher, "Grade 6 West")
        enroll(clazz, alice)
        enroll(clazz, bob)
        val token = token(teacher)

        val today = LocalDate.now(zone)
        var aliceAbsent = 0
        var bobAbsent = 0
        for (offset in 13 downTo 0) {
            val day = today.minusDays(offset.toLong())
            val aliceStatus = if (offset % 3 == 0 && aliceAbsent < 5) {
                aliceAbsent++
                "ABSENT"
            } else {
                "PRESENT"
            }
            val bobStatus = when {
                offset == 2 && bobAbsent < 1 -> {
                    bobAbsent++
                    "ABSENT"
                }
                offset == 5 -> "LATE"
                else -> "PRESENT"
            }
            seed(clazz, alice, day, aliceStatus)
            seed(clazz, bob, day, bobStatus)
        }

        val startMillis = today.minusDays(13).atStartOfDay(zone).toInstant().toEpochMilli()
        val endMillis = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val analytics = objectMapper.readValue(
            mockMvc.perform(
                get("/teacher/classes/${clazz.id}/attendance/analytics/range")
                    .param("startDate", startMillis.toString()).param("endDate", endMillis.toString())
                    .header("Authorization", auth(token))
            ).andExpect(status().isOk).andReturn().response.contentAsString,
            AttendanceAnalyticsPayload::class.java,
        )
        check(analytics.trendPoints.size == 14)
        check(analytics.averageAttendance in 0.0..100.0)
        check(analytics.weeklyHeatmap.keys.all { it in 1..7 })
        check(analytics.monthlyHeatmap.isNotEmpty())
        check(analytics.chronicAbsenteeismAlerts.any { it.studentId == alice.id.toString() && it.severity == "HIGH" })
        check(analytics.chronicAbsenteeismAlerts.none { it.studentId == bob.id.toString() })

        // A single known Monday at 50% (outside the 14-day window) must land on
        // weekly key 1 (Monday first).
        val monday = today.minusDays(21).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        seed(clazz, alice, monday, "PRESENT")
        seed(clazz, bob, monday, "ABSENT")
        val dayMillis = monday.atStartOfDay(zone).toInstant().toEpochMilli()
        val single = objectMapper.readValue(
            mockMvc.perform(
                get("/teacher/classes/${clazz.id}/attendance/analytics/range")
                    .param("startDate", dayMillis.toString()).param("endDate", dayMillis.toString())
                    .header("Authorization", auth(token))
            ).andExpect(status().isOk).andReturn().response.contentAsString,
            AttendanceAnalyticsPayload::class.java,
        )
        check(single.weeklyHeatmap[1] == 50.0)
        check(single.monthlyHeatmap[monday.dayOfMonth] == 50.0)
    }

    private fun submission(creator: UserEntity, student: UserEntity, percentage: Int, at: LocalDate) {
        val exam = ExamEntity()
        exam.title = "Attendance Maths " + percentage
        exam.subject = "Mathematics"
        exam.createdBy = creator.id
        exam.schoolId = schoolId
        exam.durationMinutes = 60
        exam.questionCount = 10
        examRepository.save(exam)
        examSubmissionRepository.save(ExamSubmissionEntity().apply {
            this.examId = exam.id
            userId = student.id
            score = percentage
            totalPoints = 100
            this.percentage = percentage
            correctCount = percentage
            questionCount = 100
            timeTakenSeconds = 600
            submittedAt = at.atStartOfDay(zone).toInstant().plusSeconds(3600)
        })
    }

    @Test
    fun `auto-mark honors the minute rule and performance relates attendance to scores`() {
        val alice = user(Role.STUDENT, "Alice Mwangi", "0722000401")
        val bob = user(Role.STUDENT, "Bob Otieno", "0722000402")
        val teacher = user(Role.TEACHER, "Class Teacher", "0722000403", subRole = SubRole.CTEACHER)
        val clazz = teacherClass(teacher, "Grade 7 Central")
        enroll(clazz, alice)
        enroll(clazz, bob)
        val token = token(teacher)
        val today = LocalDate.now(zone)

        val live = LiveClassEntity()
        live.teacherId = teacher.id
        live.teacherName = teacher.name
        live.schoolId = schoolId
        live.title = "Live Maths"
        live.subject = "Mathematics"
        live.description = ""
        live.scheduledStart = Instant.now()
        live.scheduledEnd = Instant.now().plusSeconds(3600)
        liveClassRepository.save(live)

        val aliceAtt = LiveAttendanceEntity()
        aliceAtt.classId = live.id
        aliceAtt.studentId = alice.id
        aliceAtt.status = "PRESENT"
        aliceAtt.isPresent = true
        aliceAtt.durationMinutes = 30
        aliceAtt.joinedAt = Instant.now().minusSeconds(1800)
        aliceAtt.leftAt = Instant.now()
        liveAttendanceRepository.save(aliceAtt)

        val bobAtt = LiveAttendanceEntity()
        bobAtt.classId = live.id
        bobAtt.studentId = bob.id
        bobAtt.status = "PRESENT"
        bobAtt.isPresent = true
        bobAtt.durationMinutes = 3
        bobAtt.joinedAt = Instant.now().minusSeconds(180)
        bobAtt.leftAt = Instant.now()
        liveAttendanceRepository.save(bobAtt)

        val day = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val auto = objectMapper.readValue(
            mockMvc.perform(
                post("/teacher/classes/${clazz.id}/attendance/auto-mark")
                    .param("liveClassId", live.id.toString()).param("date", day.toString())
                    .header("Authorization", auth(token))
            ).andExpect(status().isOk).andReturn().response.contentAsString,
            Array<AttendanceRecordPayload>::class.java,
        )
        // Bob's 3-minute session is not present; only Alice is auto-marked.
        check(auto.size == 1)
        check(auto.single().studentId == alice.id.toString())
        check(auto.single().status == "PRESENT")
        check(auto.single().isAutoFromLiveClass)

        for (offset in 10 downTo 1) {
            val d = today.minusDays(offset.toLong())
            seed(clazz, alice, d, if (offset == 4) "LATE" else "PRESENT")
            seed(clazz, bob, d, if (offset % 2 == 0) "ABSENT" else "PRESENT")
        }
        submission(teacher, alice, 82, today.minusDays(5))
        submission(teacher, alice, 88, today.minusDays(2))
        submission(teacher, bob, 40, today.minusDays(3))

        val start = today.minusDays(10).atStartOfDay(zone).toInstant().toEpochMilli()
        val end = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val performance = objectMapper.readValue(
            mockMvc.perform(
                get("/teacher/classes/${clazz.id}/attendance/performance")
                    .param("startDate", start.toString()).param("endDate", end.toString())
                    .header("Authorization", auth(token))
            ).andExpect(status().isOk).andReturn().response.contentAsString,
            AttendancePerformanceAnalyticsPayload::class.java,
        )
        check(performance.students.size == 2)
        val aliceRow = performance.students.first { it.studentId == alice.id.toString() }
        val bobRow = performance.students.first { it.studentId == bob.id.toString() }
        check(aliceRow.attendancePercentage > bobRow.attendancePercentage)
        check(aliceRow.averageScore > bobRow.averageScore)
        check(bobRow.riskTier == "CRITICAL")
        check(performance.correlation in -1.0..1.0)
        check(performance.riskSummary.low + performance.riskSummary.medium + performance.riskSummary.high + performance.riskSummary.critical == 2)
        check(performance.trendSeries.isNotEmpty())
    }

    @Test
    fun `parent sees a linked child's register and server-computed performance`() {
        val parent = user(Role.PARENT, "Parent One", "0722000500")
        val stranger = user(Role.PARENT, "Other Parent", "0722000501")
        val alice = user(Role.STUDENT, "Alice Mwangi", "0722000502", parentId = parent.id)
        val bob = user(Role.STUDENT, "Bob Otieno", "0722000503")
        val teacher = user(Role.TEACHER, "Class Teacher", "0722000504", subRole = SubRole.CTEACHER)
        val clazz = teacherClass(teacher, "Grade 8 East")
        enroll(clazz, alice)
        enroll(clazz, bob)

        val today = LocalDate.now(zone)
        val statuses = listOf("PRESENT", "PRESENT", "EXCUSED", "ABSENT", "PRESENT")
        statuses.forEachIndexed { index, status ->
            seed(clazz, alice, today.minusDays((statuses.size - 1 - index).toLong()), status)
        }
        for (offset in 4 downTo 0) seed(clazz, bob, today.minusDays(offset.toLong()), "PRESENT")
        submission(teacher, alice, 90, today.minusDays(2))
        submission(teacher, bob, 50, today.minusDays(2))

        val parentToken = token(parent)
        val strangerToken = token(stranger)

        val records = objectMapper.readValue(
            mockMvc.perform(get("/parent/child/${alice.id}/attendance").header("Authorization", auth(parentToken)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            Array<ParentAttendanceRecordPayload>::class.java,
        )
        check(records.size == 5)
        check(records.first().date >= records.last().date)
        check(records.any { it.status == "EXCUSED" })
        check(records.first { it.status == "ABSENT" }.isPresent.not())

        val performance = objectMapper.readValue(
            mockMvc.perform(get("/parent/child/${alice.id}/attendance/performance").header("Authorization", auth(parentToken)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            ChildAttendancePerformancePayload::class.java,
        )
        check(performance.attendancePercentage == 75.0)
        check(performance.averageScore == 90.0)
        check(performance.benchmarkAverage == 50.0)
        check(performance.riskTier == "HIGH")
        check(performance.missedDaysCount == 1)
        check(performance.impactInsight.isNotBlank())
        check(performance.trendSeries.size == 5)

        // A parent who is not linked to the child is forbidden.
        mockMvc.perform(get("/parent/child/${alice.id}/attendance").header("Authorization", auth(strangerToken)))
            .andExpect(status().isForbidden)
    }

    @Test
    fun `export-pdf renders and stores a real PDF register`() {
        val alice = user(Role.STUDENT, "Alice Mwangi", "0722000601")
        val bob = user(Role.STUDENT, "Bob Otieno", "0722000602")
        val teacher = user(Role.TEACHER, "Class Teacher", "0722000603", subRole = SubRole.CTEACHER)
        val clazz = teacherClass(teacher, "Grade 9 West")
        enroll(clazz, alice)
        enroll(clazz, bob)
        val token = token(teacher)
        val today = LocalDate.now(zone)
        seed(clazz, alice, today, "PRESENT")
        seed(clazz, bob, today, "ABSENT")

        val from = today.minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val to = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val pdf = objectMapper.readValue(
            mockMvc.perform(
                get("/teacher/classes/${clazz.id}/attendance/export-pdf")
                    .param("startDate", from.toString()).param("endDate", to.toString())
                    .header("Authorization", auth(token))
            ).andExpect(status().isOk).andReturn().response.contentAsString,
            PdfResultPayload::class.java,
        )
        check(pdf.fileUrl.contains("/media/"))
        check(pdf.fileName.endsWith(".pdf"))
        check(pdf.fileSize > 0)
        check(pdf.generatedAt > 0)

        val filename = pdf.fileUrl.substringAfterLast("/media/")
        val bytes = mockMvc.perform(get("/media/${filename}").header("Authorization", auth(token)))
            .andExpect(status().isOk).andReturn().response.contentAsByteArray
        check(bytes.size.toLong() == pdf.fileSize)
        check(bytes.decodeToString(0, 5) == "%PDF-")
    }
}




