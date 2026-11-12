package com.afrithecus.brainbox.api.attendance

import com.afrithecus.brainbox.api.attendance.entity.AttendanceRecordEntity
import com.afrithecus.brainbox.api.attendance.entity.SchoolSmsBudgetEntity
import com.afrithecus.brainbox.api.attendance.model.AttendanceSmsStatus
import com.afrithecus.brainbox.api.attendance.model.AttendanceStatus
import com.afrithecus.brainbox.api.attendance.repository.AttendanceRecordRepository
import com.afrithecus.brainbox.api.attendance.repository.AttendanceSmsDispatchRepository
import com.afrithecus.brainbox.api.attendance.repository.SchoolSmsBudgetRepository
import com.afrithecus.brainbox.api.attendance.web.AttendanceSmsSchoolSummary
import com.afrithecus.brainbox.api.auth.web.AuthResponse
import com.afrithecus.brainbox.api.classes.entity.TeacherClassEntity
import com.afrithecus.brainbox.api.classes.repository.TeacherClassRepository
import com.afrithecus.brainbox.api.identity.PlatformPermission
import com.afrithecus.brainbox.api.identity.entity.SchoolEntity
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.model.AccountKind
import com.afrithecus.brainbox.api.identity.model.Role
import com.afrithecus.brainbox.api.identity.model.SubRole
import com.afrithecus.brainbox.api.identity.model.SubscriptionStatus
import com.afrithecus.brainbox.api.identity.model.SubscriptionTier
import com.afrithecus.brainbox.api.identity.repository.SchoolRepository
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import com.afrithecus.brainbox.api.notification.repository.NotificationRepository
import com.afrithecus.brainbox.api.profile.entity.UserSettingsEntity
import com.afrithecus.brainbox.api.profile.repository.UserSettingsRepository
import com.afrithecus.brainbox.api.profile.web.UserSettingsPayload
import com.afrithecus.brainbox.api.push.entity.DeviceTokenEntity
import com.afrithecus.brainbox.api.push.repository.DeviceTokenRepository
import com.afrithecus.brainbox.api.subscription.entity.SubscriptionEntity
import com.afrithecus.brainbox.api.subscription.repository.SubscriptionRepository
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * Phase 3 dispatch mechanics (P3.4) and surfaces (P3.5): exactly-once on replay, one message per
 * family, budget draw-down and exhaustion alert, capability/consent/subscription gates, the
 * opt-in contract and the console/school endpoints. Uses the logging gateway, so no network.
 */
@SpringBootTest(
    properties = [
        "app.attendance.sms.enabled=true",
        "app.attendance.sms.provider=logging",
        "app.attendance.sms.send-after=10:30",
        "app.attendance.sms.window-start=08:00",
        "app.attendance.sms.window-end=17:00",
        "app.attendance.sms.school-days=MONDAY,TUESDAY,WEDNESDAY,THURSDAY,FRIDAY,SATURDAY,SUNDAY",
        "app.attendance.sms.budget-default-kes=2000",
        "app.attendance.sms.cost-per-sms-kes=0.20",
        "app.attendance.sms.low-balance-threshold-kes=20",
        // Keep the real scheduler out of the test JVM; the tests drive the service directly.
        "app.attendance.sms.scan-initial-delay-ms=86400000",
        "app.attendance.sms.scan-ms=86400000",
    ],
)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AttendanceSmsDispatchTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val dispatchService: AttendanceSmsDispatchService,
    @Autowired private val users: UserRepository,
    @Autowired private val schools: SchoolRepository,
    @Autowired private val classes: TeacherClassRepository,
    @Autowired private val records: AttendanceRecordRepository,
    @Autowired private val dispatches: AttendanceSmsDispatchRepository,
    @Autowired private val budgets: SchoolSmsBudgetRepository,
    @Autowired private val subscriptions: SubscriptionRepository,
    @Autowired private val settings: UserSettingsRepository,
    @Autowired private val deviceTokens: DeviceTokenRepository,
    @Autowired private val notifications: NotificationRepository,
    @Autowired private val passwordEncoder: PasswordEncoder,
) {

    private val zone: ZoneId = ZoneId.of("Africa/Nairobi")
    private val day: LocalDate = LocalDate.now(zone)
    private val now: Instant = day.atTime(11, 0).atZone(zone).toInstant()
    private lateinit var school: SchoolEntity
    private lateinit var clazz: TeacherClassEntity
    private var phoneSeq = 0

    @BeforeEach
    fun setUp() {
        school = schools.save(SchoolEntity().apply { name = "Alliance High School"; isActive = true })
        val teacher = account(Role.TEACHER, "Class Teacher", SubRole.CTEACHER)
        clazz = classes.save(TeacherClassEntity().apply {
            teacherUserId = teacher.id
            schoolId = school.id
            name = "Grade 4 South"
            gradeLevel = "Grade 4"
            subject = "Mathematics"
            isActive = true
        })
    }

    private fun nextPhone(): String = "0733" + (100000 + phoneSeq++).toString()

    private fun account(
        role: Role,
        name: String,
        subRole: SubRole? = null,
        phone: String = nextPhone(),
    ): UserEntity = users.save(UserEntity().apply {
        phoneNumber = phone
        email = phone + "@smstest.test"
        passwordHash = passwordEncoder.encode("password123") ?: error("encode")
        this.name = name
        this.role = role
        this.subRole = subRole
        schoolId = this@AttendanceSmsDispatchTests.school.id
        isActive = true
        isVerified = true
        accountKind = AccountKind.FULL
    })

    private fun learner(subscribed: Boolean = true): UserEntity {
        val learner = account(Role.STUDENT, "Alice Mwangi").apply {
            phoneVerifiedAt = now
            users.save(this)
        }
        if (subscribed) {
            subscriptions.save(SubscriptionEntity().apply {
                userId = learner.id
                status = SubscriptionStatus.ACTIVE
                tier = SubscriptionTier.PRO
                expiryDate = now.plusSeconds(30L * 24 * 60 * 60)
            })
        }
        settings.save(UserSettingsEntity().apply {
            userId = learner.id
            attendanceSmsOptIn = true
        })
        return learner
    }

    private fun absentRow(student: UserEntity, status: AttendanceStatus = AttendanceStatus.ABSENT): AttendanceRecordEntity =
        records.save(AttendanceRecordEntity().apply {
            classId = clazz.id
            studentId = student.id
            schoolId = school.id
            attendanceDate = day
            this.status = status
        })

    private fun token(user: UserEntity): String {
        val body = mockMvc.perform(
            post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"identifier\":\"" + user.phoneNumber + "\",\"password\":\"password123\"}"),
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return objectMapper.readValue(body, AuthResponse::class.java).sessionToken!!
    }

    private fun term() = AttendanceTerm.label(day)

    @Test
    fun `sends one SMS for an absent learner and never duplicates on replay`() {
        val learner = learner()
        absentRow(learner)

        check(dispatchService.dispatchDue(now) == 1)
        val row = dispatches.findAll().single()
        check(row.status == AttendanceSmsStatus.SENT)
        check(row.phoneNumber == learner.phoneNumber)
        check(row.provider == "logging")

        // A scheduler restart/replay must not buy a second message or a second budget debit.
        check(dispatchService.dispatchDue(now) == 0)
        check(dispatches.findAll().size == 1)
        val budget = budgets.findBySchoolIdAndTermLabel(school.id, term())!!
        check(budget.unitsSent == 1)
        check(budget.usedKes.compareTo(BigDecimal("0.20")) == 0)
    }

    @Test
    fun `nothing is sent before register close, for LATE, or when the row was corrected`() {
        val learner = learner()
        absentRow(learner)

        check(dispatchService.dispatchDue(day.atTime(9, 0).atZone(zone).toInstant()) == 0)
        check(dispatchService.dispatchDue(now) == 1)

        val late = learner()
        absentRow(late, AttendanceStatus.LATE)
        check(dispatchService.dispatchDue(now) == 0)

        val corrected = learner()
        val row = absentRow(corrected)
        row.status = AttendanceStatus.PRESENT
        records.save(row)
        check(dispatchService.dispatchDue(now) == 0)
        check(dispatches.findAll().size == 1)
    }

    @Test
    fun `capability consent subscription and verification each block the paid channel`() {
        // A working app: push already covers this guardian.
        val withApp = learner()
        deviceTokens.save(DeviceTokenEntity().apply {
            userId = withApp.id
            token = UUID.randomUUID().toString()
            platform = "ANDROID"
        })
        absentRow(withApp)
        check(dispatchService.dispatchDue(now) == 0)

        // No consent.
        val noConsent = learner()
        settings.findByUserId(noConsent.id)!!.attendanceSmsOptIn = false
        settings.save(settings.findByUserId(noConsent.id)!!)
        absentRow(noConsent)
        check(dispatchService.dispatchDue(now) == 0)

        // Lapsed subscription beyond the D1 grace window.
        val lapsed = learner(subscribed = false)
        subscriptions.save(SubscriptionEntity().apply {
            userId = lapsed.id
            status = SubscriptionStatus.EXPIRED
            tier = SubscriptionTier.PRO
            expiryDate = now.minusSeconds(30L * 24 * 60 * 60)
        })
        absentRow(lapsed)
        check(dispatchService.dispatchDue(now) == 0)

        // Unverified phone.
        val unverified = learner().apply {
            phoneVerifiedAt = null
            users.save(this)
        }
        absentRow(unverified)
        check(dispatchService.dispatchDue(now) == 0)

        check(dispatches.findAll().isEmpty())
    }

    @Test
    fun `siblings sharing a guardian receive one message for the day`() {
        val parent = account(Role.PARENT, "Guardian Mwangi", phone = "0722111222").apply {
            phoneVerifiedAt = now
            users.save(this)
        }
        settings.save(UserSettingsEntity().apply { userId = parent.id; attendanceSmsOptIn = true })
        val first = learner().apply { parentUserId = parent.id; users.save(this) }
        val second = learner().apply { parentUserId = parent.id; users.save(this) }
        absentRow(first)
        absentRow(second)

        check(dispatchService.dispatchDue(now) == 1)
        check(dispatches.findAll().single().phoneNumber == parent.phoneNumber)
    }

    @Test
    fun `exhausted budget sends nothing, alerts the ICT admin once and leaves push alone`() {
        val admin = account(Role.TEACHER, "ICT Admin", SubRole.ICT_ADMIN)
        budgets.save(SchoolSmsBudgetEntity().apply {
            schoolId = school.id
            termLabel = term()
            budgetKes = BigDecimal("0.20")
            usedKes = BigDecimal("0.20")
        })
        val learner = learner()
        absentRow(learner)

        check(dispatchService.dispatchDue(now) == 0)
        check(dispatches.findAll().isEmpty())
        val alerts = notifications.findAllByUserIdAndIsArchivedFalseOrderByCreatedAtDesc(admin.id)
            .filter { it.title == "Attendance SMS balance low" }
        check(alerts.size == 1)

        // One alert per term, not one per sweep.
        check(dispatchService.dispatchDue(now) == 0)
        check(notifications.findAllByUserIdAndIsArchivedFalseOrderByCreatedAtDesc(admin.id).size == 1)
    }

    @Test
    fun `profile opt-in round-trips, records consent and exposes availability`() {
        val learner = learner()
        // The fixture starts opted in for the dispatch tests; this test starts from D2's default.
        settings.findByUserId(learner.id)!!.apply { attendanceSmsOptIn = false; attendanceSmsConsentAt = null }
            .also(settings::save)
        val auth = "Bearer " + token(learner)
        val before = mockMvc.perform(
            get("/profile/settings/" + learner.id).header("Authorization", auth),
        ).andExpect(status().isOk).andReturn().response.contentAsString
        val defaults = objectMapper.readValue(before, UserSettingsPayload::class.java)
        check(defaults.attendanceSmsEnabled)
        check(!defaults.attendanceSmsOptIn)
        check(defaults.attendanceSmsConsentAt == null)

        val patched = mockMvc.perform(
            patch("/profile/settings/" + learner.id).header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"attendanceSmsOptIn\":true}"),
        ).andExpect(status().isOk).andReturn().response.contentAsString
        val optedIn = objectMapper.readValue(patched, UserSettingsPayload::class.java)
        check(optedIn.attendanceSmsOptIn)
        check(optedIn.attendanceSmsConsentAt != null) { "consent evidence must be recorded" }

        val optedOut = mockMvc.perform(
            patch("/profile/settings/" + learner.id).header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"attendanceSmsOptIn\":false}"),
        ).andExpect(status().isOk).andReturn().response.contentAsString
        val after = objectMapper.readValue(optedOut, UserSettingsPayload::class.java)
        check(!after.attendanceSmsOptIn)
        check(after.attendanceSmsConsentAt != null) { "consent history is never erased" }
    }

    @Test
    fun `console reads and sets the term allocation and the school panel reads its own summary`() {
        val operator = account(Role.ADMIN, "Platform Operator").apply {
            platformPermissions = PlatformPermission.format(
                setOf(PlatformPermission.PLATFORM_ADMIN, PlatformPermission.SCHOOLS_READ, PlatformPermission.SCHOOLS_MANAGE),
            )
            schoolId = null
            users.save(this)
        }
        val operatorToken = token(operator)

        mockMvc.perform(
            get("/admin/console/sms/schools").header("Authorization", "Bearer " + operatorToken),
        ).andExpect(status().isOk)

        val updated = mockMvc.perform(
            post("/admin/console/sms/schools/" + school.id + "/budget?term=" + term())
                .header("Authorization", "Bearer " + operatorToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"budgetKes\":500.00}"),
        ).andExpect(status().isOk).andReturn().response.contentAsString
        val summary = objectMapper.readValue(updated, AttendanceSmsSchoolSummary::class.java)
        check(summary.budgetKes == "500.00")
        check(summary.term == term())

        val ictAdmin = account(Role.TEACHER, "School ICT Admin", SubRole.ICT_ADMIN)
        val panel = mockMvc.perform(
            get("/school/attendance-sms/summary").header("Authorization", "Bearer " + token(ictAdmin)),
        ).andExpect(status().isOk).andReturn().response.contentAsString
        val own = objectMapper.readValue(panel, AttendanceSmsSchoolSummary::class.java)
        check(own.schoolId == school.id.toString())
        check(own.budgetKes == "500.00")
    }
}
