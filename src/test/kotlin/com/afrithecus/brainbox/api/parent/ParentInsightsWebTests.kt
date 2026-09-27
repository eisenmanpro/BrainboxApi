package com.afrithecus.brainbox.api.parent

import com.afrithecus.brainbox.api.auth.web.AuthResponse
import com.afrithecus.brainbox.api.classes.entity.ClassMembershipEntity
import com.afrithecus.brainbox.api.classes.entity.TeacherClassEntity
import com.afrithecus.brainbox.api.classes.repository.ClassMembershipRepository
import com.afrithecus.brainbox.api.classes.repository.TeacherClassRepository
import com.afrithecus.brainbox.api.gradebook.web.GradebookAssessmentPayload
import com.afrithecus.brainbox.api.gradebook.web.GradebookEntryPayload
import com.afrithecus.brainbox.api.homework.entity.HomeworkEntity
import com.afrithecus.brainbox.api.homework.entity.HomeworkSubmissionEntity
import com.afrithecus.brainbox.api.homework.model.SubmissionStatus
import com.afrithecus.brainbox.api.homework.repository.HomeworkRepository
import com.afrithecus.brainbox.api.homework.repository.HomeworkSubmissionRepository
import com.afrithecus.brainbox.api.identity.entity.SchoolEntity
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.model.Role
import com.afrithecus.brainbox.api.identity.model.SubRole
import com.afrithecus.brainbox.api.identity.repository.SchoolRepository
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import com.afrithecus.brainbox.api.parent.web.HomeworkItemPayload
import com.afrithecus.brainbox.api.parent.web.SubjectPerformancePayload
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
import java.time.Instant
import java.util.UUID

/**
 * The parent dashboard reads must be backed by real school records and must stay scoped to
 * a linked child. This seeds one published mark, one graded homework submission and one
 * homework still due, then checks the aggregate routes and the cross-guardian guard.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ParentInsightsWebTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val users: UserRepository,
    @Autowired private val schools: SchoolRepository,
    @Autowired private val classes: TeacherClassRepository,
    @Autowired private val memberships: ClassMembershipRepository,
    @Autowired private val homework: HomeworkRepository,
    @Autowired private val submissions: HomeworkSubmissionRepository,
    @Autowired private val passwordEncoder: PasswordEncoder,
    @Autowired private val messaging: com.afrithecus.brainbox.api.messaging.MessagingService,
) {

    private lateinit var schoolId: UUID
    private lateinit var classId: UUID
    private lateinit var teacher: UserEntity
    private lateinit var parent: UserEntity
    private lateinit var child: UserEntity
    private lateinit var parentToken: String
    private lateinit var strangerToken: String

    @BeforeEach
    fun setUp() {
        val school = SchoolEntity().apply {
            name = "Insights School " + UUID.randomUUID().toString().take(6)
            isActive = true
        }
        schools.save(school)
        schoolId = school.id

        teacher = account(Role.TEACHER, "Class Teacher", SubRole.CTEACHER)
        parent = account(Role.PARENT, "Guardian")
        val stranger = account(Role.PARENT, "Other Guardian")
        child = account(Role.STUDENT, "Pupil Insights")
        child.parentUserId = parent.id
        users.save(child)
        parentToken = token(parent)
        check(parent.schoolId == teacher.schoolId) {
            "fixture problem: guardian school ${parent.schoolId} vs teacher school ${teacher.schoolId}"
        }
        strangerToken = token(stranger)

        val clazz = classes.save(
            TeacherClassEntity().apply {
                teacherUserId = teacher.id
                schoolId = schoolId
                name = "Grade 7 North"
                gradeLevel = "Grade 7"
                subject = "Mathematics"
                isActive = true
            }
        )
        classId = clazz.id
        memberships.save(ClassMembershipEntity().apply { classId = clazz.id; studentId = child.id })

        val teacherToken = token(teacher)
        publishMark(teacherToken, clazz.id, teacher)
        seedHomework(clazz.id, teacher)
    }

    @Test
    fun `the dashboard reads are computed from real records for a linked child`() {
        val performance = parentList("/parent/child/${child.id}/performance", SubjectPerformancePayload::class.java)
        check(performance.size == 1) { "expected one subject row, got ${performance.size}" }
        check(performance.single().subject == "Mathematics") { "grouped by class subject, was ${performance.single().subject}" }
        check(performance.single().score == 90.0) { "one 90% mark, was ${performance.single().score}" }

        val stats = objectMapper.readValue(
            parentGet("/parent/child/${child.id}/stats").andReturn().response.contentAsString,
            com.afrithecus.brainbox.api.parent.web.ChildStatsPayload::class.java,
        )
        check(stats.avgScore == 90) { "average mark, was ${stats.avgScore}" }
        check(stats.totalXP >= 0)

        val homework = parentList("/parent/child/${child.id}/homework", HomeworkItemPayload::class.java)
        check(homework.size == 2) { "expected the graded and the open homework, got ${homework.size}" }
        check(homework.any { it.status == "GRADED" }) { "the handed-in homework must read GRADED" }
        check(homework.any { it.status == "NOT_STARTED" }) { "the open homework must read NOT_STARTED" }

        val weekly = objectMapper.readValue(
            parentGet("/parent/child/${child.id}/weekly-performance").andReturn().response.contentAsString,
            com.afrithecus.brainbox.api.parent.web.WeeklyPerformancePayload::class.java,
        )
        check(weekly.scores.size == 7) { "a week of scores, got ${weekly.scores.size}" }
        check(weekly.scores.last() == 90) { "today's mark lands on the last day, was ${weekly.scores}" }

        val activities = parentList(
            "/parent/child/${child.id}/activities",
            com.afrithecus.brainbox.api.parent.web.RecentActivityPayload::class.java,
        )
        check(activities.any { it.type == "GRADE_PUBLISHED" }) { "a published mark is an activity" }
        check(activities.any { it.type == "HOMEWORK_SUBMITTED" }) { "a submission is an activity" }

        val engagement = objectMapper.readValue(
            parentGet("/parent/child/${child.id}/engagement").andReturn().response.contentAsString,
            com.afrithecus.brainbox.api.parent.web.EngagementPayload::class.java,
        )
        check(engagement.weekly in 0..100)
        check(engagement.monthly in 0..100)
        check(engagement.factors.isNotEmpty()) { "engagement explains itself" }

        // No attendance registers and no low marks in this fixture, so no alerts.
        val alerts = parentList(
            "/parent/alerts/${child.id}",
            com.afrithecus.brainbox.api.parent.web.ParentAlertPayload::class.java,
        )
        check(alerts.isEmpty()) { "a 90% mark is not an alert, got $alerts" }
    }

    @Test
    fun `a guardian who is not linked to the child reads nothing`() {
        for (path in listOf(
            "/parent/child/${child.id}/performance",
            "/parent/child/${child.id}/stats",
            "/parent/child/${child.id}/activities",
            "/parent/child/${child.id}/weekly-performance",
            "/parent/child/${child.id}/upcoming-events",
            "/parent/child/${child.id}/homework",
            "/parent/child/${child.id}/engagement",
            "/parent/child/${child.id}/cbc-ratings",
            "/parent/alerts/${child.id}",
        )) {
            mockMvc.perform(get(path).header("Authorization", auth(strangerToken)))
                .andExpect(status().isNotFound)
        }
    }

    /**
     * Sibling comparison reads only the guardian's own linked children, and each metric
     * is the same number the child's own dashboard shows (or null when there is no data,
     * so a child is never ranked on an invented zero).
     */
    @Test
    fun `siblings are compared on the records their own dashboards use`() {
        val second = account(Role.STUDENT, "Pupil Sibling")
        second.parentUserId = parent.id
        second.gradeLevel = "Grade 4"
        users.save(second)

        val raw = parentGet("/parent/family/comparison").andReturn().response.contentAsString
        check(raw.contains("generatedAt")) { "the payload must carry its generation time" }
        val comparison = objectMapper.readValue(
            raw,
            com.afrithecus.brainbox.api.parent.web.FamilyComparisonPayload::class.java,
        )
        check(comparison.children.size == 2) { "expected both children, got ${comparison.children.size}" }

        val first = comparison.children.first { it.childId == child.id.toString() }
        check(first.averageScore == 90) { "the graded child reads their 90% mark, was ${first.averageScore}" }
        check(first.engagementScore != null) { "engagement is computed from the same week formula" }
        check(first.grade == child.gradeLevel) { "the child's own grade travels with the row" }

        // The sibling has no marks and no attendance yet: null, not 0.
        val sibling = comparison.children.first { it.childId == second.id.toString() }
        check(sibling.averageScore == null) { "no marks must read null, was ${sibling.averageScore}" }
        check(sibling.attendanceRate == null) { "no register must read null, was ${sibling.attendanceRate}" }
        // Badges come from the gamification profile, so the comparison must agree with
        // the child's own achievements read rather than inventing a count.
        val siblingAchievements = objectMapper.readValue(
            parentGet("/parent/child/${second.id}/achievements").andReturn().response.contentAsString,
            com.afrithecus.brainbox.api.achievements.web.UserAchievementsPayload::class.java,
        )
        check(sibling.badges == siblingAchievements.badges.size) {
            "badges must match the child's own read, was ${sibling.badges} vs ${siblingAchievements.badges.size}"
        }
        val firstAchievements = objectMapper.readValue(
            parentGet("/parent/child/${child.id}/achievements").andReturn().response.contentAsString,
            com.afrithecus.brainbox.api.achievements.web.UserAchievementsPayload::class.java,
        )
        check(first.badges == firstAchievements.badges.size) { "the comparison agrees for every child" }

        // The stranger sees only their own (empty) family, never the other guardian's children.
        val strangerView = objectMapper.readValue(
            mockMvc.perform(get("/parent/family/comparison").header("Authorization", auth(strangerToken)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            com.afrithecus.brainbox.api.parent.web.FamilyComparisonPayload::class.java,
        )
        check(strangerView.children.isEmpty()) { "a guardian with no linked child compares nothing" }

        // A learner has no family to compare.
        val learnerToken = token(second)
        mockMvc.perform(get("/parent/family/comparison").header("Authorization", auth(learnerToken)))
            .andExpect(status().isForbidden)
        mockMvc.perform(get("/parent/family/comparison")).andExpect(status().isUnauthorized)
    }

    @Test
    fun `the portal exposes achievements, guidance, the contract and teacher messages`() {
        val achievements = objectMapper.readValue(
            parentGet("/parent/child/${child.id}/achievements").andReturn().response.contentAsString,
            com.afrithecus.brainbox.api.achievements.web.UserAchievementsPayload::class.java,
        )
        check(achievements.userId == child.id.toString()) { "achievements must be the child's, was ${achievements.userId}" }

        // A child with a 90% mark, no attendance concerns and no overdue work gets the
        // reassuring entry rather than a fabricated alarm. A failing subject does alarm.
        val guidance = parentList(
            "/parent/child/${child.id}/recommendations",
            com.afrithecus.brainbox.api.parent.web.ParentRecommendationPayload::class.java,
        )
        check(guidance.isNotEmpty()) { "the dashboard always has something to say" }
        check(guidance.all { it.ctaRoute.isNotBlank() && it.priority in listOf("HIGH", "MEDIUM", "LOW") })
        check(guidance.none { it.priority == "HIGH" }) { "a 90% child has no high-priority item, got $guidance" }

        // No contract has been authored for this child.
        mockMvc.perform(get("/parent/child/${child.id}/contract").header("Authorization", auth(parentToken)))
            .andExpect(status().isNotFound)

        // A class-wide parent note reaches the guardian through the child's inbox.
        messaging.teacherSend(
            teacher,
            com.afrithecus.brainbox.api.messaging.web.TeacherSendMessageRequest(
                audienceType = "CLASS_PARENTS",
                audienceId = classId.toString(),
                subject = "Fractions test",
                body = "We wrote a fractions test today.",
            ),
            null,
        )
        val messages = parentList(
            "/parent/messages/${child.id}",
            com.afrithecus.brainbox.api.parent.web.ParentMessagePayload::class.java,
        )
        check(messages.any { it.fromTeacher == teacher.name && it.subject == "Fractions test" }) {
            "the flagged class note must reach the guardian, got $messages"
        }

        // The guardian can write back to the class teacher.
        val replyResponse = mockMvc.perform(
            post("/parent/message").header("Authorization", auth(parentToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"childId":"${child.id}","message":"Thank you, we will revise at home.","subject":"Re: Fractions test"}""")
        ).andReturn()
        check(replyResponse.response.status == 200) {
            "parent reply failed with ${replyResponse.response.status}: ${replyResponse.response.contentAsString}"
        }
        val reply = objectMapper.readValue(
            replyResponse.response.contentAsString,
            com.afrithecus.brainbox.api.parent.web.ParentMessagePayload::class.java,
        )
        check(reply.message.contains("revise at home"))
        check(reply.fromTeacher == teacher.name)
    }

    // ---------------------------------------------------------------- helpers

    private fun publishMark(teacherToken: String, classId: UUID, teacher: UserEntity) {
        mockMvc.perform(
            post("/teacher/classes/$classId/assessments").header("Authorization", auth(teacherToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        GradebookAssessmentPayload(
                            id = "assess_insight",
                            classId = classId.toString(),
                            title = "Pop quiz",
                            assessmentType = "QUIZ",
                            maxScore = 50,
                            dateAssigned = System.currentTimeMillis(),
                            term = com.afrithecus.brainbox.api.traditional.model.ExamTerm.TERM_1,
                            isPublished = true,
                            countsTowardAverage = true,
                        )
                    )
                )
        ).andExpect(status().isOk)

        mockMvc.perform(
            post("/teacher/gradebook").header("Authorization", auth(teacherToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        GradebookEntryPayload(
                            id = "gb_insight",
                            classId = classId.toString(),
                            teacherId = teacher.id.toString(),
                            assessmentId = "assess_insight",
                            assessmentType = "QUIZ",
                            studentId = child.id.toString(),
                            studentName = child.name,
                            rawScore = 45,
                            maxScore = 50,
                            percentage = 90,
                            assessmentTitle = "Pop quiz",
                            gradedAt = System.currentTimeMillis(),
                        )
                    )
                )
        ).andExpect(status().isOk)
    }

    private fun seedHomework(classId: UUID, teacher: UserEntity) {
        val graded = homework.save(
            HomeworkEntity().apply {
                id = "hw_insight_graded"
                this.classId = classId
                teacherId = teacher.id
                teacherName = teacher.name
                schoolId = schoolId
                title = "Fractions worksheet"
                subject = "Mathematics"
                gradeLevel = 7
                dueDate = Instant.now().minusSeconds(86_400)
                isActive = true
                isDraft = false
            }
        )
        homework.save(
            HomeworkEntity().apply {
                id = "hw_insight_open"
                this.classId = classId
                teacherId = teacher.id
                teacherName = teacher.name
                schoolId = schoolId
                title = "Decimals revision"
                subject = "Mathematics"
                gradeLevel = 7
                dueDate = Instant.now().plusSeconds(86_400)
                isActive = true
                isDraft = false
            }
        )
        submissions.save(
            HomeworkSubmissionEntity().apply {
                homeworkId = graded.id
                studentId = child.id
                status = SubmissionStatus.GRADED
                grade = 8
                submittedAt = Instant.now()
            }
        )
    }

    private fun parentGet(path: String) =
        mockMvc.perform(get(path).header("Authorization", auth(parentToken)))
            .andExpect(status().isOk)

    private fun <T> parentList(path: String, type: Class<T>): List<T> {
        val json = parentGet(path).andReturn().response.contentAsString
        val listType = objectMapper.typeFactory.constructCollectionType(List::class.java, type)
        return objectMapper.readValue(json, listType)
    }

    private fun account(role: Role, name: String, subRole: SubRole? = null): UserEntity {
        // Captured outside the apply block: an unqualified `schoolId` inside it would
        // resolve to the entity's own (null) property.
        val school = schoolId
        return users.save(
            UserEntity().apply {
                phoneNumber = "078" + (3_000_000 + users.count()).toString().takeLast(7)
                email = phoneNumber + "@insights.test"
                passwordHash = passwordEncoder.encode("password123") ?: error("encode")
                this.name = name
                this.role = role
                this.subRole = subRole
                this.schoolId = school
                gradeLevel = if (role == Role.STUDENT) "Grade 7" else null
                isActive = true
                isVerified = true
            }
        )
    }

    private fun token(user: UserEntity): String {
        val body = mockMvc.perform(
            post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("""{"identifier":"${user.phoneNumber}","password":"password123"}""")
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return objectMapper.readValue(body, AuthResponse::class.java).sessionToken!!
    }

    private fun auth(token: String) = "Bearer " + token
}
