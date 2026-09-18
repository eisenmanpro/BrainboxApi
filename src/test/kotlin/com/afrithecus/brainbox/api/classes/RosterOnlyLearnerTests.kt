package com.afrithecus.brainbox.api.classes

import com.afrithecus.brainbox.api.auth.web.AuthResponse
import com.afrithecus.brainbox.api.classes.entity.TeacherClassEntity
import com.afrithecus.brainbox.api.classes.repository.ClassMembershipRepository
import com.afrithecus.brainbox.api.classes.repository.TeacherClassRepository
import com.afrithecus.brainbox.api.classes.web.BulkProvisionLearnersRequest
import com.afrithecus.brainbox.api.classes.web.BulkProvisionLearnersResult
import com.afrithecus.brainbox.api.classes.web.EnableAppAccessResult
import com.afrithecus.brainbox.api.classes.web.ProvisionLearnerRequest
import com.afrithecus.brainbox.api.classes.web.ProvisionedLearnerPayload
import com.afrithecus.brainbox.api.identity.SchoolConfigService
import com.afrithecus.brainbox.api.identity.entity.SchoolEntity
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.model.AccountKind
import com.afrithecus.brainbox.api.identity.model.CurrentUser
import com.afrithecus.brainbox.api.identity.model.Role
import com.afrithecus.brainbox.api.identity.model.SubRole
import com.afrithecus.brainbox.api.identity.repository.SchoolRepository
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import com.afrithecus.brainbox.api.report.GradeTableSpec
import com.afrithecus.brainbox.api.report.ReportBranding
import com.afrithecus.brainbox.api.report.ReportDataService
import com.afrithecus.brainbox.api.report.TraditionalStudentsSpec
import com.afrithecus.brainbox.api.report.web.ReportGenerationRequestPayload
import com.afrithecus.brainbox.api.report.web.ReportType
import com.afrithecus.brainbox.api.traditional.TraditionalExamService
import com.afrithecus.brainbox.api.traditional.model.ExamTerm
import com.afrithecus.brainbox.api.traditional.model.TraditionalExamStatus
import com.afrithecus.brainbox.api.traditional.repository.TraditionalExamRepository
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.util.UUID

/**
 * Roster-only learners: a class teacher (or a coordinator/ICT admin in the same
 * school) can provision a pupil who has no smartphone so the traditional exam
 * engine can mark, rank, analyse and print reports for them, while the account
 * itself can never log in until it is deliberately upgraded.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class RosterOnlyLearnerTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val userRepository: UserRepository,
    @Autowired private val schoolRepository: SchoolRepository,
    @Autowired private val passwordEncoder: PasswordEncoder,
    @Autowired private val teacherClassRepository: TeacherClassRepository,
    @Autowired private val classMembershipRepository: ClassMembershipRepository,
    @Autowired private val traditional: TraditionalExamService,
    @Autowired private val examRepository: TraditionalExamRepository,
    @Autowired private val reportData: ReportDataService,
    @Autowired private val schoolConfig: SchoolConfigService,
) {

    private lateinit var schoolId: UUID
    private lateinit var otherSchoolId: UUID

    @BeforeEach
    fun setUp() {
        schoolId = school("Roster Test School")
        otherSchoolId = school("Roster Other School")
    }

    @Test
    fun provisionsALoginLessLearnerWhoJoinsTheExamRoster() {
        val owner = user(Role.TEACHER, "Owner Teacher", "0700000101", schoolId)
        val clazz = clazz(owner, "Grade 4", "4B", schoolId)
        val ownerToken = token(owner)

        val learner = provision(clazz.id, ownerToken, "Achieng Otieno", "BB-T-0001", "Mama Otieno")

        check(learner.accountKind == AccountKind.ROSTER_ONLY.name)
        check(learner.created)
        check(learner.admissionNumber == "BB-T-0001")
        check(learner.guardianName == "Mama Otieno")

        val stored = userRepository.findById(UUID.fromString(learner.id)).orElseThrow()
        check(stored.phoneNumber == null)
        check(stored.passwordHash.isEmpty())
        check(stored.accountKind == AccountKind.ROSTER_ONLY)
        check(stored.provisionedBy == owner.id)
        check(stored.gradeLevel == "Grade 4")
        check(stored.schoolId == schoolId)
        check(stored.isActive)
        check(classMembershipRepository.findByClassIdAndStudentId(clazz.id, stored.id) != null)

        // The grade-wide traditional roster (marks, rankings, combined/class reports).
        val exam = exam(owner)
        val roster = traditional.students(CurrentUser(owner.id, Role.TEACHER, null), exam.examId, null)
        check(roster.any { it.id == learner.id }) { "roster-only learner must be in the exam roster" }
    }

    @Test
    fun provisioningIsIdempotentByAdmissionNumberAndByName() {
        val owner = user(Role.TEACHER, "Owner Teacher", "0700000102", schoolId)
        val clazz = clazz(owner, "Grade 4", "4B", schoolId)
        val ownerToken = token(owner)

        val first = provision(clazz.id, ownerToken, "Brian Mwangi", "BB-T-0002")
        val again = provision(clazz.id, ownerToken, "Brian Mwangi", "BB-T-0002")
        check(!again.created)
        check(again.id == first.id)
        check(userRepository.findByStudentAdmissionNumber("BB-T-0002")!!.id == UUID.fromString(first.id))

        val noAdmissionFirst = provision(clazz.id, ownerToken, "Cynthia Wairimu", null)
        val noAdmissionAgain = provision(clazz.id, ownerToken, "cynthia wairimu", null)
        check(!noAdmissionAgain.created) { "an identical name in the class must not duplicate" }
        check(noAdmissionAgain.id == noAdmissionFirst.id)
    }

    @Test
    fun bulkProvisioningReportsBadRowsAndKeepsGoodOnes() {
        val owner = user(Role.TEACHER, "Owner Teacher", "0700000103", schoolId)
        val clazz = clazz(owner, "Grade 4", "4B", schoolId)
        val ownerToken = token(owner)

        // A learner in another school already owns this admission number.
        val foreign = user(Role.STUDENT, "Foreign Pupil", "0700000199", otherSchoolId, admission = "TAKEN-1")

        val body = objectMapper.writeValueAsString(
            BulkProvisionLearnersRequest(
                learners = listOf(
                    ProvisionLearnerRequest("Good One", "GOOD-1"),
                    ProvisionLearnerRequest("Collides", "TAKEN-1"),
                    ProvisionLearnerRequest("Good Two", "GOOD-2"),
                )
            )
        )
        val result = read(
            mockMvc.perform(
                post("/teacher/classes/" + clazz.id + "/learners/bulk")
                    .header("Authorization", auth(ownerToken))
                    .contentType(MediaType.APPLICATION_JSON).content(body)
            ).andExpect(status().isOk).andReturn().response.contentAsString,
            BulkProvisionLearnersResult::class.java,
        )
        check(result.created == 2) { "two good rows must land, was " + result.created }
        check(result.errors.size == 1)
        check(result.errors.single().name == "Collides")
        check(userRepository.findByStudentAdmissionNumber("TAKEN-1")!!.id == foreign.id) { "no cross-school clash" }
        check(userRepository.findByStudentAdmissionNumber("GOOD-1") != null)
        check(userRepository.findByStudentAdmissionNumber("GOOD-2") != null)
    }

    @Test
    fun aCoordinatorCanProvisionWhileAForeignTeacherCannot() {
        val coordinator = user(Role.TEACHER, "Coordinator", "0700000104", schoolId, subRole = SubRole.GRADE_COORDINATOR)
        val owner = user(Role.TEACHER, "Owner Teacher", "0700000105", schoolId)
        val clazz = clazz(owner, "Grade 4", "4B", schoolId)

        val coordinatorToken = token(coordinator)
        val byCoordinator = provision(clazz.id, coordinatorToken, "Coordinator Pupil", "BB-T-0004")
        check(byCoordinator.accountKind == AccountKind.ROSTER_ONLY.name)

        val foreign = user(Role.TEACHER, "Foreign Teacher", "0700000198", otherSchoolId)
        val foreignToken = token(foreign)
        mockMvc.perform(
            post("/teacher/classes/" + clazz.id + "/learners")
                .header("Authorization", auth(foreignToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(ProvisionLearnerRequest("Not Allowed", null)))
        ).andExpect(status().isForbidden)
    }

    @Test
    fun aRosterOnlyLearnerCannotLogInButIsMarkedRankedAndReported() {
        val owner = user(Role.TEACHER, "Owner Teacher", "0700000106", schoolId)
        val coordinator = user(Role.TEACHER, "Coordinator", "0700000107", schoolId, subRole = SubRole.GRADE_COORDINATOR)
        val clazz = clazz(owner, "Grade 4", "4B", schoolId)
        val ownerToken = token(owner)
        val learner = provision(clazz.id, ownerToken, "David Kiptoo", "BB-T-0005")

        // Cannot authenticate, even knowing the admission number.
        mockMvc.perform(
            post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"identifier\":\"BB-T-0005\",\"password\":\"whatever123\"}")
        ).andExpect(status().isUnauthorized)

        // A coordinator marks the whole grade, includes the learner in analysis,
        // and can build both the combined and the per-learner printable reports.
        val exam = exam(owner)
        val coordinatorUser = CurrentUser(coordinator.id, Role.TEACHER, SubRole.GRADE_COORDINATOR)
        traditional.saveMarks(
            coordinatorUser,
            exam.examId,
            listOf(MarkEntryDto(studentId = learner.id, subjectId = "MAT", rawScore = 72)),
        )

        val ranking = traditional.gradeWideRanking(coordinatorUser, exam.examId)
        check(ranking.any { it.studentId == learner.id }) { "analysis must include the roster-only learner" }

        // Reports are published-only; publishing has its own lifecycle tests, so flip
        // the status directly here and exercise the report/print path itself.
        publishDirectly(exam.examId)
        val report = traditional.myResult(coordinatorUser, exam.examId, learner.id)
        check(report.studentId == learner.id)

        val branding = ReportBranding.from(schoolConfig.branding(schoolId))
        val combined = reportData.build(
            ReportGenerationRequestPayload(reportType = ReportType.TRADITIONAL_COMBINED, examId = exam.examId),
            coordinatorUser,
            branding,
        )
        check(combined is GradeTableSpec) { "combined report must be a grade table" }
        check(combined.rows.any { it.studentId == learner.id }) {
            "the combined report must include the roster-only learner"
        }

        val printable = reportData.build(
            ReportGenerationRequestPayload(
                reportType = ReportType.TRADITIONAL_STUDENT,
                examId = exam.examId,
                studentIds = listOf(learner.id),
            ),
            coordinatorUser,
            branding,
        )
        check(printable is TraditionalStudentsSpec) { "student report must be a students spec" }
        check(printable.reports.single().studentId == learner.id)
    }

    @Test
    fun enablingAppAccessUpgradesTheSameRecordAndLoginThenWorks() {
        val owner = user(Role.TEACHER, "Owner Teacher", "0700000108", schoolId)
        val clazz = clazz(owner, "Grade 4", "4B", schoolId)
        val ownerToken = token(owner)
        val learner = provision(clazz.id, ownerToken, "Esther Njeri", "BB-T-0006")

        val upgraded = read(
            mockMvc.perform(
                post("/teacher/classes/learners/" + learner.id + "/enable-app-access")
                    .header("Authorization", auth(ownerToken))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"phoneNumber\":\"0712345678\"}")
            ).andExpect(status().isOk).andReturn().response.contentAsString,
            EnableAppAccessResult::class.java,
        )
        check(upgraded.accountKind == AccountKind.FULL.name)
        check(upgraded.temporaryPassword != null) { "a generated password must be returned once" }

        val stored = userRepository.findById(UUID.fromString(learner.id)).orElseThrow()
        check(stored.id.toString() == learner.id) { "the record is kept, not replaced" }
        check(stored.accountKind == AccountKind.FULL)
        check(stored.phoneNumber == "0712345678")

        mockMvc.perform(
            post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"identifier\":\"0712345678\",\"password\":\"" + upgraded.temporaryPassword + "\"}")
        ).andExpect(status().isOk)

        // A second upgrade is refused, and the phone is now taken.
        mockMvc.perform(
            post("/teacher/classes/learners/" + learner.id + "/enable-app-access")
                .header("Authorization", auth(ownerToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"phoneNumber\":\"0712345679\"}")
        ).andExpect(status().isBadRequest)
    }

    @Test
    fun deactivatingRemovesTheLearnerFromListsButKeepsMarks() {
        val owner = user(Role.TEACHER, "Owner Teacher", "0700000109", schoolId)
        val clazz = clazz(owner, "Grade 4", "4B", schoolId)
        val ownerToken = token(owner)
        val learner = provision(clazz.id, ownerToken, "Faith Chebet", "BB-T-0007")

        val exam = exam(owner)
        // The class owner cannot enter marks for their own class; a coordinator does.
        val coordinator = user(Role.TEACHER, "Marks Coordinator", "0700000110", schoolId, subRole = SubRole.GRADE_COORDINATOR)
        val coordinatorUser = CurrentUser(coordinator.id, Role.TEACHER, SubRole.GRADE_COORDINATOR)
        traditional.saveMarks(coordinatorUser, exam.examId, listOf(MarkEntryDto(studentId = learner.id, subjectId = "MAT", rawScore = 55)))

        mockMvc.perform(
            delete("/teacher/classes/" + clazz.id + "/learners/" + learner.id)
                .header("Authorization", auth(ownerToken))
        ).andExpect(status().isNoContent)

        val stored = userRepository.findById(UUID.fromString(learner.id)).orElseThrow()
        check(!stored.isActive)
        check(classMembershipRepository.findByClassIdAndStudentId(clazz.id, stored.id) == null)
        val roster = traditional.students(coordinatorUser, exam.examId, null)
        check(roster.none { it.id == learner.id }) { "a deactivated learner leaves the active exam roster" }
        check(traditional.marks(coordinatorUser, exam.examId).any { it.studentId == learner.id }) {
            "marks survive deactivation so a report can still be produced"
        }
    }

    // ------------------------------------------------------------- fixtures

    private fun school(name: String): UUID = schoolRepository.save(
        SchoolEntity().apply {
            this.name = name
            isActive = true
        }
    ).id

    private fun user(
        role: Role,
        name: String,
        phone: String,
        school: UUID,
        subRole: SubRole? = null,
        admission: String? = null,
    ): UserEntity = userRepository.save(
        UserEntity().apply {
            phoneNumber = phone
            email = phone + "@roster.test"
            passwordHash = passwordEncoder.encode("password123") ?: error("encode")
            this.name = name
            this.role = role
            this.subRole = subRole
            this.schoolId = school
            gradeLevel = if (role == Role.STUDENT) "Grade 4" else null
            studentAdmissionNumber = admission
            isVerified = true
            isActive = true
        }
    )

    private fun clazz(owner: UserEntity, grade: String, name: String, school: UUID): TeacherClassEntity =
        teacherClassRepository.save(
            TeacherClassEntity().apply {
                teacherUserId = owner.id
                this.schoolId = school
                this.name = name
                gradeLevel = grade
                subject = "Mathematics"
                isActive = true
            }
        )

    private fun exam(creator: UserEntity): TraditionalExamDto = traditional.createExam(
        CurrentUser(creator.id, creator.role, creator.subRole),
        CreateTraditionalExamRequest(
            title = "End Term 1 2026 - Grade 4",
            term = ExamTerm.TERM_1,
            gradeLevel = "Grade 4",
            year = 2026,
            subjects = listOf(SubjectConfigDto(subjectId = "MAT", name = "Mathematics", maxScore = 100)),
        ),
    )

    private fun publishDirectly(examId: String) {
        val entity = examRepository.findById(UUID.fromString(examId)).orElseThrow()
        entity.status = TraditionalExamStatus.PUBLISHED
        entity.publishedAt = java.time.Instant.now()
        examRepository.save(entity)
    }

    private fun token(entity: UserEntity): String {
        val login = mockMvc.perform(
            post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"identifier\":\"" + entity.email + "\",\"password\":\"password123\"}")
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return objectMapper.readValue(login, AuthResponse::class.java).sessionToken!!
    }

    private fun auth(token: String) = "Bearer " + token

    private fun provision(classId: UUID, token: String, name: String, admission: String?, guardian: String? = null): ProvisionedLearnerPayload =
        read(
            mockMvc.perform(
                post("/teacher/classes/" + classId + "/learners")
                    .header("Authorization", auth(token))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(ProvisionLearnerRequest(name, admission, guardian, null)))
            ).andExpect(status().isOk).andReturn().response.contentAsString,
            ProvisionedLearnerPayload::class.java,
        )

    private inline fun <reified T> read(json: String, type: Class<T>): T = objectMapper.readValue(json, type)
}
