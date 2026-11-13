package com.afrithecus.brainbox.api.identity

import com.afrithecus.brainbox.api.analytics.AnalyticsService
import com.afrithecus.brainbox.api.attendance.AttendanceService
import com.afrithecus.brainbox.api.attendance.web.AttendanceRecordPayload
import com.afrithecus.brainbox.api.auth.TeacherAuthService
import com.afrithecus.brainbox.api.cbcratings.CbcAnalyticsService
import com.afrithecus.brainbox.api.classes.LearnerProvisioningService
import com.afrithecus.brainbox.api.classes.entity.ClassMembershipEntity
import com.afrithecus.brainbox.api.classes.entity.TeacherClassEntity
import com.afrithecus.brainbox.api.classes.repository.ClassMembershipRepository
import com.afrithecus.brainbox.api.classes.repository.TeacherClassRepository
import com.afrithecus.brainbox.api.classes.web.ProvisionLearnerRequest
import com.afrithecus.brainbox.api.common.error.ApiErrorCode
import com.afrithecus.brainbox.api.common.error.ApiException
import com.afrithecus.brainbox.api.gradebook.GradebookService
import com.afrithecus.brainbox.api.identity.entity.SchoolEntity
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.model.AccountStatus
import com.afrithecus.brainbox.api.identity.model.CurrentUser
import com.afrithecus.brainbox.api.identity.model.Role
import com.afrithecus.brainbox.api.identity.model.SubRole
import com.afrithecus.brainbox.api.identity.repository.SchoolRepository
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import com.afrithecus.brainbox.api.report.ReportGenerationService
import com.afrithecus.brainbox.api.report.StudentReportService
import com.afrithecus.brainbox.api.report.web.ReportGenerationRequestPayload
import com.afrithecus.brainbox.api.report.web.ReportType
import com.afrithecus.brainbox.api.report.web.StudentReportRequestPayload
import com.afrithecus.brainbox.api.studentanalytics.StudentAnalyticsService
import com.afrithecus.brainbox.api.subscription.SubscriptionService
import com.afrithecus.brainbox.api.teacher.TeacherDashboardService
import com.afrithecus.brainbox.api.timetable.ScheduleChangeService
import com.afrithecus.brainbox.api.timetable.web.ScheduleChangePayload
import com.afrithecus.brainbox.api.traditional.TraditionalExamService
import com.afrithecus.brainbox.api.traditional.entity.TraditionalExamEntity
import com.afrithecus.brainbox.api.traditional.model.ExamTerm
import com.afrithecus.brainbox.api.traditional.model.TraditionalExamStatus
import com.afrithecus.brainbox.api.traditional.repository.TraditionalExamRepository
import com.afrithecus.brainbox.api.traditional.web.CreateTraditionalExamRequest
import com.afrithecus.brainbox.api.traditional.web.SubjectConfigDto
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Grade-scope authority for a GRADE_COORDINATOR (task-1): a coordinator is a class teacher
 * with grade-wide duties, exactly one per grade, and their elevated authority is limited to
 * their own school + grade. This suite proves, service by service, that
 * (a) the coordinator's own grade is allowed, (b) another grade is refused, and
 * (c) ordinary assignment-based teaching access in another grade still works.
 *
 * A platform ADMIN / ICT_ADMIN stay school-wide (checked in the approval test).
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class CoordinatorGradeScopeTests(
    @Autowired private val users: UserRepository,
    @Autowired private val schools: SchoolRepository,
    @Autowired private val classes: TeacherClassRepository,
    @Autowired private val memberships: ClassMembershipRepository,
    @Autowired private val exams: TraditionalExamRepository,
    @Autowired private val passwordEncoder: PasswordEncoder,
    @Autowired private val studentAnalytics: StudentAnalyticsService,
    @Autowired private val cbc: CbcAnalyticsService,
    @Autowired private val attendance: AttendanceService,
    @Autowired private val gradebook: GradebookService,
    @Autowired private val analytics: AnalyticsService,
    @Autowired private val subscriptions: SubscriptionService,
    @Autowired private val teacherAuth: TeacherAuthService,
    @Autowired private val dashboard: TeacherDashboardService,
    @Autowired private val provisioning: LearnerProvisioningService,
    @Autowired private val scheduleChanges: ScheduleChangeService,
    @Autowired private val traditional: TraditionalExamService,
    @Autowired private val reportGeneration: ReportGenerationService,
    @Autowired private val studentReports: StudentReportService,
) {

    private lateinit var schoolId: UUID
    private lateinit var coordinator: UserEntity
    private lateinit var ictAdmin: UserEntity
    private lateinit var teacher: UserEntity
    private lateinit var grade4Student: UserEntity
    private lateinit var grade9Student: UserEntity
    private lateinit var grade4Class: TeacherClassEntity
    private lateinit var grade9Class: TeacherClassEntity
    private lateinit var coordinatorsGrade9Class: TeacherClassEntity

    @BeforeEach
    fun setUp() {
        schoolId = schools.save(SchoolEntity().apply {
            name = "Coordinator Scope School " + (1000..9999).random()
            isActive = true
        }).id
        coordinator = account(SubRole.GRADE_COORDINATOR, "Coordinator", "Grade 4")
        ictAdmin = account(SubRole.ICT_ADMIN, "ICT Admin", null)
        teacher = account(null, "Class Teacher", "Grade 4")
        grade4Class = klass(teacher, "Grade 4", "Grade 4 North")
        grade9Class = klass(teacher, "Grade 9", "Grade 9 East")
        coordinatorsGrade9Class = klass(coordinator, "Grade 9", "Grade 9 West")
        grade4Student = learner("Amina Grade4", "Grade 4")
        grade9Student = learner("Brian Grade9", "Grade 9")
        join(grade4Class, grade4Student)
        join(grade9Class, grade9Student)
    }

    // ------------------------------------------------------------- students / analytics

    @Test
    fun `student analytics allow the coordinator's grade, refuse another grade, keep teaching access`() {
        val current = current(coordinator)
        check(studentAnalytics.studentAnalytics(coordinator, grade4Student.id.toString()).studentId ==
            grade4Student.id.toString())
        expectForbidden { studentAnalytics.studentAnalytics(coordinator, grade9Student.id.toString()) }
        expectForbidden { studentAnalytics.classComparisons(coordinator, grade9Class.id.toString()) }

        // Additive: the same Grade 9 learner sits in a class the coordinator actually teaches.
        join(coordinatorsGrade9Class, grade9Student)
        studentAnalytics.studentAnalytics(coordinator, grade9Student.id.toString())
        studentAnalytics.classComparisons(coordinator, coordinatorsGrade9Class.id.toString())
        check(current.userId == coordinator.id) { "sanity: coordinator principal" }
    }

    @Test
    fun `class and student analytics reads are grade scoped`() {
        val current = current(coordinator)
        analytics.classAnalytics(current, grade4Class.id.toString())
        expectForbidden { analytics.classAnalytics(current, grade9Class.id.toString()) }
        analytics.classAnalytics(current, coordinatorsGrade9Class.id.toString())

        analytics.studentPerformance(current, grade4Student.id.toString())
        expectForbidden { analytics.studentPerformance(current, grade9Student.id.toString()) }
        join(coordinatorsGrade9Class, grade9Student)
        analytics.studentPerformance(current, grade9Student.id.toString())
    }

    @Test
    fun `cbc class and learner reports are grade scoped`() {
        cbc.classReport(coordinator, grade4Class.id.toString(), "TERM_1")
        expectForbidden { cbc.classReport(coordinator, grade9Class.id.toString(), "TERM_1") }
        cbc.classReport(coordinator, coordinatorsGrade9Class.id.toString(), "TERM_1")
        cbc.studentReport(coordinator, grade4Student.id.toString(), "TERM_1")
        expectForbidden { cbc.studentReport(coordinator, grade9Student.id.toString(), "TERM_1") }
    }

    @Test
    fun `gradebook access is grade scoped`() {
        val current = current(coordinator)
        gradebook.assessments(current, grade4Class.id.toString())
        expectForbidden { gradebook.assessments(current, grade9Class.id.toString()) }
        gradebook.assessments(current, coordinatorsGrade9Class.id.toString())
    }

    @Test
    fun `attendance reads and writes are grade scoped`() {
        val current = current(coordinator)
        val now = System.currentTimeMillis()
        attendance.register(current, grade4Class.id.toString(), now)
        expectForbidden { attendance.register(current, grade9Class.id.toString(), now) }
        attendance.register(current, coordinatorsGrade9Class.id.toString(), now)
        expectForbidden { attendance.analytics(current, grade9Class.id.toString(), now - 86_400_000L, now) }

        // Writing to a foreign grade is refused; the coordinator's own grade is accepted.
        val foreign = AttendanceRecordPayload(
            id = "att_9", classId = grade9Class.id.toString(), studentId = grade9Student.id.toString(),
            studentName = grade9Student.name, date = now, status = "PRESENT",
        )
        expectForbidden { attendance.submit(current, listOf(foreign)) }
        val own = foreign.copy(id = "att_4", classId = grade4Class.id.toString(), studentId = grade4Student.id.toString())
        check(attendance.submit(current, listOf(own)).isNotEmpty())
    }

    // ------------------------------------------------------------- exam/report surfaces

    @Test
    fun `paper exam sessions and lifecycle are grade scoped`() {
        val current = current(coordinator)
        val request = CreateTraditionalExamRequest(
            title = "End Term - Grade 4", term = ExamTerm.TERM_1, gradeLevel = "Grade 4", year = 2026,
            subjects = listOf(SubjectConfigDto(subjectId = "MAT", name = "Mathematics", maxScore = 100)),
        )
        val created = traditional.createExam(current, request)
        check(created.gradeLevel == "Grade 4")

        val foreign = request.copy(title = "End Term - Grade 9", gradeLevel = "Grade 9")
        expectForbidden { traditional.createExam(current, foreign) }

        // Grade 9 sessions are visible only because this coordinator also teaches a Grade 9
        // class (additive teaching access, exactly like a plain teacher of that grade). A
        // grade they neither coordinate nor teach is never listed.
        listOf("Grade 9" to "Grade 9 End Term", "Grade 10" to "Grade 10 End Term").forEach { (grade, title) ->
            exams.save(TraditionalExamEntity().apply {
                this.title = title; term = ExamTerm.TERM_1; gradeLevel = grade; year = 2026
                status = TraditionalExamStatus.CONFIRMED; schoolId = this@CoordinatorGradeScopeTests.schoolId
                createdBy = this@CoordinatorGradeScopeTests.teacher.id
            })
        }
        val visible = traditional.listExams(current, null)
        check(visible.none { it.gradeLevel == "Grade 10" }) {
            "a coordinator must not list a grade they neither coordinate nor teach, got " + visible.map { it.gradeLevel }
        }
        check(visible.any { it.gradeLevel == "Grade 4" }) { "own grade must be listed" }
    }

    @Test
    fun `report hub refuses and student report requests are grade scoped`() {
        val current = current(coordinator)
        val foreign = ReportGenerationRequestPayload(
            reportType = ReportType.CBC_CLASS, classId = grade9Class.id.toString(), term = "TERM_1", year = 2026,
        )
        expectForbidden { reportGeneration.submit(current, foreign, null) }
        val own = foreign.copy(classId = grade4Class.id.toString())
        reportGeneration.submit(current, own, null)

        expectForbidden {
            studentReports.generate(
                current,
                StudentReportRequestPayload(reportType = ReportType.CBC_STUDENT, studentId = grade9Student.id.toString()),
                null,
            )
        }
        studentReports.generate(
            current,
            StudentReportRequestPayload(reportType = ReportType.CBC_STUDENT, studentId = grade4Student.id.toString()),
            null,
        )
    }

    @Test
    fun `the teacher dashboard counts only the coordinator's grade`() {
        listOf("Grade 4" to "Grade 4 Counted", "Grade 9" to "Grade 9 Hidden").forEach { (grade, title) ->
            exams.save(TraditionalExamEntity().apply {
                this.title = title; term = ExamTerm.TERM_1; gradeLevel = grade; year = 2026
                status = TraditionalExamStatus.CONFIRMED; schoolId = this@CoordinatorGradeScopeTests.schoolId
                createdBy = teacher.id
            })
        }
        val payload = dashboard.dashboard(current(coordinator))
        check(payload.pendingFinalizationsCount == 1) {
            "dashboard must count only Grade 4 finalizations, got " + payload.pendingFinalizationsCount
        }
    }

    // ------------------------------------------------------------- people / money / moves

    @Test
    fun `learner approval is grade scoped while an ICT admin stays school-wide`() {
        teacherAuth.decideUser(current(coordinator), grade4Student.id.toString(), AccountStatus.VERIFIED, "Approved learner")
        expectForbidden {
            teacherAuth.decideUser(current(coordinator), grade9Student.id.toString(), AccountStatus.VERIFIED, "Approved learner")
        }
        teacherAuth.decideUser(current(ictAdmin), grade9Student.id.toString(), AccountStatus.VERIFIED, "Approved learner")
    }

    @Test
    fun `billing views are private except for a learner in the coordinator's grade`() {
        subscriptions.viewFor(current(coordinator), grade4Student.id)
        expectForbidden { subscriptions.viewFor(current(coordinator), grade9Student.id) }
        expectForbidden { subscriptions.viewFor(current(coordinator), teacher.id) }
        // The ICT admin keeps school-wide visibility.
        subscriptions.viewFor(current(ictAdmin), grade9Student.id)
    }

    @Test
    fun `roster provisioning is grade scoped but the coordinator's own class stays available`() {
        provisioning.provision(coordinator, grade4Class.id.toString(), ProvisionLearnerRequest(name = "Provisioned Four"))
        provisioning.provision(coordinator, coordinatorsGrade9Class.id.toString(), ProvisionLearnerRequest(name = "Provisioned Nine"))
        expectForbidden {
            provisioning.provision(coordinator, grade9Class.id.toString(), ProvisionLearnerRequest(name = "Foreign Nine"))
        }
    }

    @Test
    fun `schedule change review only covers the coordinator's grade`() {
        val teacherFour = account(null, "Teacher Four", "Grade 4")
        val teacherNine = account(null, "Teacher Nine", "Grade 9")
        listOf(teacherFour to "sch_4", teacherNine to "sch_9").forEach { (actor, id) ->
            scheduleChanges.submit(actor, change(id))
        }
        val visible = scheduleChanges.list(coordinator, null)
        check(visible.map { it.id } == listOf("sch_4")) {
            "a coordinator must only see their grade's requests, got " + visible.map { it.id }
        }
        expectForbidden { scheduleChanges.approve(coordinator, "sch_9") }
        check(scheduleChanges.approve(coordinator, "sch_4").status == "APPROVED")
        // The ICT admin keeps school-wide review.
        check(scheduleChanges.list(ictAdmin, null).map { it.id }.toSet() == setOf("sch_4", "sch_9"))
    }

    // ------------------------------------------------------------- fixtures / helpers

    private fun account(subRole: SubRole?, name: String, grade: String?): UserEntity {
        val suffix = (100000..999999).random()
        return users.save(UserEntity().apply {
            phoneNumber = "07" + suffix
            email = "cg" + suffix + "@coordinator.test"
            passwordHash = passwordEncoder.encode("password123") ?: error("encode")
            this.name = name
            role = Role.TEACHER
            this.subRole = subRole
            schoolId = this@CoordinatorGradeScopeTests.schoolId
            gradeLevel = grade
            isVerified = true
            isActive = true
        })
    }

    private fun learner(name: String, grade: String): UserEntity {
        val suffix = (100000..999999).random()
        return users.save(UserEntity().apply {
            phoneNumber = "07" + suffix
            email = "cgl" + suffix + "@coordinator.test"
            passwordHash = passwordEncoder.encode("password123") ?: error("encode")
            this.name = name
            role = Role.STUDENT
            schoolId = this@CoordinatorGradeScopeTests.schoolId
            gradeLevel = grade
            isVerified = true
            isActive = true
        })
    }

    private fun klass(owner: UserEntity, grade: String, name: String): TeacherClassEntity =
        classes.save(TeacherClassEntity().apply {
            teacherUserId = owner.id
            schoolId = this@CoordinatorGradeScopeTests.schoolId
            this.name = name
            gradeLevel = grade
            subject = "Mathematics"
            isActive = true
        })

    private fun join(clazz: TeacherClassEntity, student: UserEntity) {
        memberships.save(ClassMembershipEntity().apply { classId = clazz.id; studentId = student.id })
    }

    private fun current(user: UserEntity) = CurrentUser(user.id, user.role, user.subRole)

    private fun change(id: String) = ScheduleChangePayload(
        id = id, day = "Monday", className = "Grade 4 South", subject = "Mathematics",
        startTime = "08:00", endTime = "09:00", reason = "Medical appointment",
    )

    private inline fun expectForbidden(block: () -> Unit) {
        val error = runCatching(block).exceptionOrNull()
        check(error is ApiException && error.code == ApiErrorCode.FORBIDDEN) {
            "expected FORBIDDEN, got " + error
        }
    }
}
