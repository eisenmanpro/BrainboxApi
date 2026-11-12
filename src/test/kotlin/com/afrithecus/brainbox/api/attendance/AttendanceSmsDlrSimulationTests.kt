package com.afrithecus.brainbox.api.attendance

import com.afrithecus.brainbox.api.attendance.entity.AttendanceRecordEntity
import com.afrithecus.brainbox.api.attendance.entity.AttendanceSmsDispatchEntity
import com.afrithecus.brainbox.api.attendance.model.AttendanceSmsStatus
import com.afrithecus.brainbox.api.attendance.model.AttendanceStatus
import com.afrithecus.brainbox.api.attendance.repository.AttendanceRecordRepository
import com.afrithecus.brainbox.api.attendance.repository.AttendanceSmsDispatchRepository
import com.afrithecus.brainbox.api.attendance.web.AttendanceSmsDlrSimulationController
import com.afrithecus.brainbox.api.attendance.web.SimulateDlrRequest
import com.afrithecus.brainbox.api.classes.entity.TeacherClassEntity
import com.afrithecus.brainbox.api.classes.repository.TeacherClassRepository
import com.afrithecus.brainbox.api.identity.entity.SchoolEntity
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.model.AccountKind
import com.afrithecus.brainbox.api.identity.model.Role
import com.afrithecus.brainbox.api.identity.repository.SchoolRepository
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import io.micrometer.core.instrument.MeterRegistry
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.mock.env.MockEnvironment
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * P6.0: the staging-only DLR simulator writes through the same normalised path as the provider
 * webhook, requires the shared secret, is idempotent, and cannot be reached outside the staging
 * profile. Uses the logging gateway, so no network.
 */
@SpringBootTest(
    properties = [
        "app.attendance.sms.enabled=true",
        "app.attendance.sms.provider=logging",
        "app.attendance.sms.dlr-secret=test-secret",
        "app.attendance.sms.dlr-simulation-enabled=true",
        "app.attendance.sms.dlr-simulation-delay-seconds=0",
        "app.attendance.sms.dlr-simulation-outcome=DELIVERED",
        "app.attendance.sms.dlr-simulation-limit=10",
        "app.attendance.sms.school-days=MONDAY,TUESDAY,WEDNESDAY,THURSDAY,FRIDAY,SATURDAY,SUNDAY",
        "app.attendance.sms.scan-initial-delay-ms=86400000",
        "app.attendance.sms.scan-ms=86400000",
        "app.attendance.sms.dlr-simulation-initial-delay-ms=86400000",
        "app.attendance.sms.dlr-simulation-scan-ms=86400000",
    ],
)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AttendanceSmsDlrSimulationTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val simulator: AttendanceSmsDlrSimulator,
    @Autowired private val dlrService: AttendanceSmsDlrService,
    @Autowired private val properties: AttendanceSmsProperties,
    @Autowired private val dispatches: AttendanceSmsDispatchRepository,
    @Autowired private val records: AttendanceRecordRepository,
    @Autowired private val users: UserRepository,
    @Autowired private val schools: SchoolRepository,
    @Autowired private val classes: TeacherClassRepository,
    @Autowired private val passwordEncoder: PasswordEncoder,
    @Autowired private val clock: Clock,
    @Autowired private val meterRegistry: MeterRegistry,
) {

    private val zone: ZoneId = ZoneId.of("Africa/Nairobi")
    private val day: LocalDate = LocalDate.now(zone)
    private lateinit var school: SchoolEntity
    private lateinit var clazz: TeacherClassEntity
    private lateinit var dispatch: AttendanceSmsDispatchEntity
    private var phoneSeq = 0

    @BeforeEach
    fun setUp() {
        school = schools.save(SchoolEntity().apply { name = "Alliance High School"; isActive = true })
        val teacher = users.save(UserEntity().apply {
            phoneNumber = nextPhone()
            email = phoneNumber + "@simdlr.test"
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
        dispatch = newSentDispatch("hp-sim-1")
    }

    private fun nextPhone(): String = "0745" + (100000 + phoneSeq++).toString()

    private fun newSentDispatch(messageId: String?): AttendanceSmsDispatchEntity {
        // One learner per dispatch: attendance_records is unique on (class, date, student) and the
        // dispatch ledger is unique on (record, recipient), so each fixture needs its own record.
        val student = users.save(UserEntity().apply {
            phoneNumber = nextPhone()
            email = phoneNumber + "@simdlr.test"
            passwordHash = passwordEncoder.encode("password123") ?: error("encode")
            name = "Learner " + phoneNumber
            role = Role.STUDENT
            schoolId = school.id
            isActive = true
            isVerified = true
            accountKind = AccountKind.FULL
        })
        val record = records.save(AttendanceRecordEntity().apply {
            classId = clazz.id
            studentId = student.id
            schoolId = school.id
            attendanceDate = day
            status = AttendanceStatus.ABSENT
        })
        return dispatches.saveAndFlush(
            AttendanceSmsDispatchEntity().apply {
                attendanceRecordId = record.id
                schoolId = school.id
                learnerId = student.id
                recipientUserId = student.id
                phoneNumber = student.phoneNumber!!
                status = AttendanceSmsStatus.SENT
                provider = "hostpinnacle"
                providerMessageId = messageId
                units = 1
                costKes = BigDecimal("0.20")
            },
        )
    }

    /** A real [MockEnvironment] with the staging profile, so the service guard is exercised. */
    private fun stagingSimulator(): AttendanceSmsDlrSimulator = AttendanceSmsDlrSimulator(
        properties,
        dlrService,
        dispatches,
        MockEnvironment().apply { setActiveProfiles("staging") },
        clock,
        meterRegistry,
    )

    private fun row(id: UUID = dispatch.id) = dispatches.findById(id).orElseThrow()

    @Test
    fun `outside the staging profile the service refuses and no simulation endpoint exists`() {
        val refused = simulator.simulate(null, dispatch.id.toString(), "DELIVERED", "test-secret")
        check(refused.outcome == AttendanceSmsDlrSimulator.SimulationOutcome.NOT_STAGING)
        check(row().dlrStatus == null) { "a non-staging call must not touch delivery state" }

        mockMvc.perform(
            post("/webhooks/sms/dlr/simulate")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-SMS-DLR-Secret", "test-secret")
                .content("{\"messageId\":\"hp-sim-1\",\"status\":\"DELIVERED\"}"),
        ).andExpect(status().isNotFound)

        check(row().dlrStatus == null)
    }

    @Test
    fun `staging simulation applies delivered and failed without a provider and is idempotent`() {
        val staging = stagingSimulator()

        val delivered = staging.simulate(null, dispatch.id.toString(), "Delivered", "test-secret")
        check(delivered.outcome == AttendanceSmsDlrSimulator.SimulationOutcome.SIMULATED)
        val first = row()
        check(first.dlrStatus == "DELIVERED")
        check(first.deliveredAt != null)
        val receivedAt = first.dlrReceivedAt

        val replay = staging.simulate(null, dispatch.id.toString(), "DELIVERED", "test-secret")
        check(replay.outcome == AttendanceSmsDlrSimulator.SimulationOutcome.SIMULATED)
        check(row().dlrReceivedAt == receivedAt) { "a simulated replay must not move the report" }

        // A wrong secret is rejected before anything is written.
        val failed = newSentDispatch("hp-sim-2")
        val unauthorized = staging.simulate(null, failed.id.toString(), "FAILED", "wrong-secret")
        check(unauthorized.outcome == AttendanceSmsDlrSimulator.SimulationOutcome.UNAUTHORIZED)
        check(row(failed.id).dlrStatus == null)

        val simulatedFailed = staging.simulate("hp-sim-2", null, "FAILED", "test-secret")
        check(simulatedFailed.outcome == AttendanceSmsDlrSimulator.SimulationOutcome.SIMULATED)
        check(row(failed.id).dlrStatus == "FAILED")
        check(row(failed.id).deliveredAt == null) { "a failed report must not claim delivery" }

        // A row with no provider message id (the logging gateway) is addressable by dispatch id.
        val noProviderId = newSentDispatch(null)
        val simulatedNoProvider = staging.simulate(null, noProviderId.id.toString(), "DELIVERED", "test-secret")
        check(simulatedNoProvider.outcome == AttendanceSmsDlrSimulator.SimulationOutcome.SIMULATED)
        check(row(noProviderId.id).dlrStatus == "DELIVERED")

        check(staging.simulate("unknown", null, "DELIVERED", "test-secret").outcome ==
            AttendanceSmsDlrSimulator.SimulationOutcome.NOT_FOUND)
        check(staging.simulate(null, "not-a-uuid", "DELIVERED", "test-secret").outcome ==
            AttendanceSmsDlrSimulator.SimulationOutcome.NOT_FOUND)
        check(staging.simulate("hp-sim-1", dispatch.id.toString(), "DELIVERED", "test-secret").outcome ==
            AttendanceSmsDlrSimulator.SimulationOutcome.BAD_REQUEST) { "exactly one identifier is required" }
        check(staging.simulate("hp-sim-1", null, "MAYBE", "test-secret").outcome ==
            AttendanceSmsDlrSimulator.SimulationOutcome.BAD_REQUEST)
    }

    @Test
    fun `the staging controller maps the outcomes to HTTP status codes`() {
        val controller = AttendanceSmsDlrSimulationController(stagingSimulator())

        val ok = controller.simulate(
            "test-secret",
            SimulateDlrRequest(dispatchId = dispatch.id.toString(), status = "DELIVERED"),
        )
        check(ok.statusCode.value() == 200)
        check(ok.body?.outcome == "SIMULATED")

        val unauthorized = controller.simulate(
            "wrong",
            SimulateDlrRequest(dispatchId = dispatch.id.toString(), status = "DELIVERED"),
        )
        check(unauthorized.statusCode.value() == 401)

        val missing = controller.simulate("test-secret", SimulateDlrRequest(messageId = "unknown"))
        check(missing.statusCode.value() == 404)

        val bad = controller.simulate(
            "test-secret",
            SimulateDlrRequest(dispatchId = dispatch.id.toString(), status = "MAYBE"),
        )
        check(bad.statusCode.value() == 400)
    }

    @Test
    fun `the staging sweep only reports pending SENT messages older than the delay`() {
        val staging = stagingSimulator()
        val alreadyReported = newSentDispatch("hp-sim-reported").apply {
            dlrStatus = "DELIVERED"
            dlrReceivedAt = clock.instant()
            dispatches.save(this)
        }
        val noProviderId = newSentDispatch(null)
        val failed = newSentDispatch("hp-sim-failed").apply {
            status = AttendanceSmsStatus.FAILED
            dispatches.save(this)
        }

        val result = staging.sweepPending()
        check(result.outcome == AttendanceSmsDlrSimulator.SimulationOutcome.SIMULATED)
        check(result.simulated == 2) { "the pending rows (with and without a provider id) qualify, got ${result.simulated}" }
        check(row().dlrStatus == "DELIVERED")
        check(row(noProviderId.id).dlrStatus == "DELIVERED")
        check(row(alreadyReported.id).dlrStatus == "DELIVERED")
        check(row(failed.id).dlrStatus == null)
    }
}
