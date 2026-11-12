package com.afrithecus.brainbox.api.attendance

import com.afrithecus.brainbox.api.attendance.entity.AttendanceRecordEntity
import com.afrithecus.brainbox.api.attendance.entity.AttendanceSmsDispatchEntity
import com.afrithecus.brainbox.api.attendance.model.AttendanceSmsStatus
import com.afrithecus.brainbox.api.attendance.model.AttendanceStatus
import com.afrithecus.brainbox.api.attendance.repository.AttendanceRecordRepository
import com.afrithecus.brainbox.api.attendance.repository.AttendanceSmsDispatchRepository
import com.afrithecus.brainbox.api.classes.entity.TeacherClassEntity
import com.afrithecus.brainbox.api.classes.repository.TeacherClassRepository
import com.afrithecus.brainbox.api.identity.entity.SchoolEntity
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.model.AccountKind
import com.afrithecus.brainbox.api.identity.model.Role
import com.afrithecus.brainbox.api.identity.repository.SchoolRepository
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * P4.5: the delivery-report webhook authenticates its shared secret, persists the outcome on the
 * dispatch row, and is idempotent on replay. Uses the logging gateway, so no network.
 */
@SpringBootTest(
    properties = [
        "app.attendance.sms.enabled=true",
        "app.attendance.sms.provider=logging",
        "app.attendance.sms.dlr-secret=test-secret",
        "app.attendance.sms.school-days=MONDAY,TUESDAY,WEDNESDAY,THURSDAY,FRIDAY,SATURDAY,SUNDAY",
        "app.attendance.sms.scan-initial-delay-ms=86400000",
        "app.attendance.sms.scan-ms=86400000",
    ],
)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AttendanceSmsDlrWebTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val users: UserRepository,
    @Autowired private val schools: SchoolRepository,
    @Autowired private val classes: TeacherClassRepository,
    @Autowired private val records: AttendanceRecordRepository,
    @Autowired private val dispatches: AttendanceSmsDispatchRepository,
    @Autowired private val passwordEncoder: PasswordEncoder,
) {

    private val zone: ZoneId = ZoneId.of("Africa/Nairobi")
    private val day: LocalDate = LocalDate.now(zone)
    private val now: Instant = Instant.now()
    private lateinit var school: SchoolEntity
    private lateinit var clazz: TeacherClassEntity
    private lateinit var learner: UserEntity
    private lateinit var dispatch: AttendanceSmsDispatchEntity
    private var phoneSeq = 0

    @BeforeEach
    fun setUp() {
        school = schools.save(SchoolEntity().apply { name = "Alliance High School"; isActive = true })
        val teacher = users.save(UserEntity().apply {
            phoneNumber = nextPhone()
            email = phoneNumber + "@dlrtest.test"
            passwordHash = passwordEncoder.encode("password123") ?: error("encode")
            name = "Class Teacher"
            role = Role.TEACHER
            schoolId = school.id
            isActive = true
            isVerified = true
            accountKind = AccountKind.FULL
        })
        clazz = classes.save(TeacherClassEntity().apply {
            teacherUserId = teacher.id
            schoolId = school.id
            name = "Grade 4 South"
            gradeLevel = "Grade 4"
            subject = "Mathematics"
            isActive = true
        })
        learner = users.save(UserEntity().apply {
            phoneNumber = nextPhone()
            email = phoneNumber + "@dlrtest.test"
            passwordHash = passwordEncoder.encode("password123") ?: error("encode")
            name = "Alice Mwangi"
            role = Role.STUDENT
            schoolId = school.id
            isActive = true
            isVerified = true
            accountKind = AccountKind.FULL
        })
        val record = records.save(AttendanceRecordEntity().apply {
            classId = clazz.id
            studentId = learner.id
            schoolId = school.id
            attendanceDate = day
            status = AttendanceStatus.ABSENT
        })
        dispatch = dispatches.saveAndFlush(AttendanceSmsDispatchEntity().apply {
            attendanceRecordId = record.id
            schoolId = school.id
            learnerId = learner.id
            recipientUserId = learner.id
            phoneNumber = learner.phoneNumber!!
            status = AttendanceSmsStatus.SENT
            provider = "hostpinnacle"
            providerMessageId = "hp-message-1"
            units = 1
            costKes = BigDecimal("0.20")
        })
    }

    private fun nextPhone(): String = "0744" + (100000 + phoneSeq++).toString()

    private fun postDlr(secret: String?, body: String) = mockMvc.perform(
        post("/webhooks/sms/dlr")
            .contentType(MediaType.APPLICATION_JSON)
            .apply { if (secret != null) header("X-SMS-DLR-Secret", secret) }
            .content(body),
    )

    @Test
    fun `a delivered report is persisted and replayed idempotently`() {
        postDlr("test-secret", "{\"messageId\":\"hp-message-1\",\"status\":\"Delivered\"}")
            .andExpect(status().isOk)
        val first = dispatches.findById(dispatch.id).orElseThrow()
        check(first.dlrStatus == "DELIVERED")
        check(first.deliveredAt != null)
        val receivedAt = first.dlrReceivedAt

        postDlr("test-secret", "{\"messageId\":\"hp-message-1\",\"status\":\"Delivered\"}")
            .andExpect(status().isOk)
        val second = dispatches.findById(dispatch.id).orElseThrow()
        check(second.dlrReceivedAt == receivedAt) { "a replay must not move the first delivery report" }
    }

    @Test
    fun `a failed report is classified without claiming delivery`() {
        postDlr("test-secret", "{\"messageId\":\"hp-message-1\",\"status\":\"Undelivered\"}")
            .andExpect(status().isOk)
        val row = dispatches.findById(dispatch.id).orElseThrow()
        check(row.dlrStatus == "FAILED")
        check(row.deliveredAt == null)
    }

    @Test
    fun `a wrong shared secret is rejected`() {
        postDlr("wrong-secret", "{\"messageId\":\"hp-message-1\",\"status\":\"Delivered\"}")
            .andExpect(status().isUnauthorized)
        check(dispatches.findById(dispatch.id).orElseThrow().dlrStatus == null)
    }

    @Test
    fun `an unknown message id is acknowledged without touching a row`() {
        postDlr("test-secret", "{\"messageId\":\"unknown\",\"status\":\"Delivered\"}")
            .andExpect(status().isOk)
        check(dispatches.findById(dispatch.id).orElseThrow().dlrStatus == null)
    }
}
