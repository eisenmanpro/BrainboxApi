package com.afrithecus.brainbox.api.exams

import com.afrithecus.brainbox.api.auth.web.AuthResponse
import com.afrithecus.brainbox.api.classes.entity.TeacherClassEntity
import com.afrithecus.brainbox.api.classes.repository.TeacherClassRepository
import com.afrithecus.brainbox.api.exams.repository.ExamRepository
import com.afrithecus.brainbox.api.exams.repository.ExamSubmissionRepository
import com.afrithecus.brainbox.api.exams.web.ExamAnalysisReportPayload
import com.afrithecus.brainbox.api.exams.web.ExamResultPayload
import com.afrithecus.brainbox.api.exams.web.ExamSessionResponse
import com.afrithecus.brainbox.api.exams.web.RemediationAssignmentPayload
import com.afrithecus.brainbox.api.exams.web.RemediationSavePayload
import com.afrithecus.brainbox.api.exams.web.TeacherExamPayload
import com.afrithecus.brainbox.api.exams.web.TeacherQuestionPayload
import com.afrithecus.brainbox.api.exams.web.TeacherReviewQueueItemPayload
import com.afrithecus.brainbox.api.identity.entity.SchoolEntity
import com.afrithecus.brainbox.api.identity.entity.UserEntity
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.util.UUID

/**
 * Teacher digital exam authoring CRUD, the essay review queue with server-side
 * delta marks, key-question analysis and replace-all remediation
 * (docs/ongoing/api_exams_changes.md).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class TeacherExamWebTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val userRepository: UserRepository,
    @Autowired private val schoolRepository: SchoolRepository,
    @Autowired private val classRepository: TeacherClassRepository,
    @Autowired private val examRepository: ExamRepository,
    @Autowired private val submissionRepository: ExamSubmissionRepository,
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

    // ------------------------------------------------------------ fixtures

    private fun user(role: Role, name: String, phone: String): UserEntity {
        val entity = UserEntity()
        entity.phoneNumber = phone
        entity.email = phone + "@exam.test"
        entity.passwordHash = passwordEncoder.encode("password123") ?: error("encode")
        entity.name = name
        entity.role = role
        entity.schoolId = schoolId
        entity.gradeLevel = "Grade 4"
        entity.isVerified = true
        entity.isActive = true
        return userRepository.save(entity)
    }

    private fun token(user: UserEntity): String {
        val body = mockMvc.perform(
            post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"identifier\":\"" + user.email + "\",\"password\":\"password123\"}")
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return objectMapper.readValue(body, AuthResponse::class.java).sessionToken!!
    }

    private fun auth(token: String) = "Bearer " + token

    private fun teacherClass(teacher: UserEntity): TeacherClassEntity =
        classRepository.save(TeacherClassEntity().apply {
            teacherUserId = teacher.id
            this.schoolId = this@TeacherExamWebTests.schoolId
            name = "Grade 4 South"
            gradeLevel = "Grade 4"
            subject = "Mathematics"
            isActive = true
        })

    private fun question(
        id: String,
        text: String,
        type: String,
        points: Int,
        options: List<String>? = null,
        correct: String? = null,
        strand: String? = null,
        key: Boolean = false,
        requiresExplanation: Boolean = false,
    ) = TeacherQuestionPayload(
        id = id,
        examId = "",
        questionText = text,
        questionType = type,
        points = points,
        difficulty = 3,
        options = options,
        correctAnswer = correct,
        cbcStrandTag = strand,
        isKeyQuestion = key,
        requiresExplanation = requiresExplanation,
    )

    private fun exam(
        id: String,
        classId: String,
        published: Boolean,
        questions: List<TeacherQuestionPayload>,
    ) = TeacherExamPayload(
        id = id,
        classId = classId,
        title = "Algebra Mid-Term",
        subject = "Mathematics",
        gradeLevel = 4,
        durationMinutes = 40,
        difficulty = 3,
        questions = questions,
        isPublished = published,
        term = "TERM_2",
    )

    private fun postExam(token: String, body: TeacherExamPayload): TeacherExamPayload {
        val response = mockMvc.perform(
            post("/teacher/exams").header("Authorization", auth(token))
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body))
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return objectMapper.readValue(response, TeacherExamPayload::class.java)
    }

    // ------------------------------------------------------------ tests

    @Test
    fun `crud is idempotent and self scoped`() {
        val teacher = user(Role.TEACHER, "Class Teacher", "0755010001")
        val other = user(Role.TEACHER, "Other Teacher", "0755010002")
        val clazz = teacherClass(teacher)
        val t1 = token(teacher)
        val t2 = token(other)

        val body = exam(
            "exam_crud_1", clazz.id.toString(), published = false,
            questions = listOf(question("q1", "2 + 2?", "MCQ", 2, options = listOf("3", "4"), correct = "__IDX__1__")),
        )
        val created = postExam(t1, body)
        check(created.id == "exam_crud_1")
        check(created.classId == clazz.id.toString())
        check(created.term == "TERM_2")
        check(created.questions.single().correctAnswer == "__IDX__1__")
        check(!created.isPublished)
        check(created.totalPoints == 2)

        // Replaying the same client id upserts rather than duplicating.
        postExam(t1, body)
        val listed = objectMapper.readValue(
            mockMvc.perform(get("/teacher/exams").header("Authorization", auth(t1)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            Array<TeacherExamPayload>::class.java,
        )
        check(listed.size == 1)

        // Update is idempotent per id and round-trips the changed title.
        val updated = postExam(
            t1,
            body.copy(title = "Algebra Final", isPublished = true),
        )
        // create() upserts; PUT is the dedicated update path.
        val viaPut = objectMapper.readValue(
            mockMvc.perform(
                put("/teacher/exams/exam_crud_1").header("Authorization", auth(t1))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(body.copy(title = "Algebra Final", isPublished = true)))
            ).andExpect(status().isOk).andReturn().response.contentAsString,
            TeacherExamPayload::class.java,
        )
        check(viaPut.title == "Algebra Final")
        check(viaPut.isPublished)
        check(updated.id == "exam_crud_1")

        // A different teacher cannot see or edit it.
        val otherList = objectMapper.readValue(
            mockMvc.perform(get("/teacher/exams").header("Authorization", auth(t2)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            Array<TeacherExamPayload>::class.java,
        )
        check(otherList.isEmpty())
        mockMvc.perform(
            put("/teacher/exams/exam_crud_1").header("Authorization", auth(t2))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body))
        ).andExpect(status().isForbidden)

        // Delete is repeat safe.
        mockMvc.perform(delete("/teacher/exams/exam_crud_1").header("Authorization", auth(t1)))
            .andExpect(status().isNoContent)
        mockMvc.perform(delete("/teacher/exams/exam_crud_1").header("Authorization", auth(t1)))
            .andExpect(status().isNoContent)
        mockMvc.perform(delete("/teacher/exams/exam_crud_1").header("Authorization", auth(t2)))
            .andExpect(status().isNoContent)
    }

    @Test
    fun `publish requires questions and is repeat safe`() {
        val teacher = user(Role.TEACHER, "Class Teacher", "0755010010")
        val clazz = teacherClass(teacher)
        val t = token(teacher)

        postExam(t, exam("exam_empty_1", clazz.id.toString(), published = false, questions = emptyList()))
        mockMvc.perform(post("/teacher/exams/exam_empty_1/publish").header("Authorization", auth(t)))
            .andExpect(status().isConflict)

        postExam(
            t,
            exam(
                "exam_publish_1", clazz.id.toString(), published = false,
                questions = listOf(question("q1", "2 + 2?", "MCQ", 2, options = listOf("3", "4"), correct = "__IDX__1__")),
            ),
        )
        val published = objectMapper.readValue(
            mockMvc.perform(post("/teacher/exams/exam_publish_1/publish").header("Authorization", auth(t)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            TeacherExamPayload::class.java,
        )
        check(published.isPublished)
        mockMvc.perform(post("/teacher/exams/exam_publish_1/publish").header("Authorization", auth(t)))
            .andExpect(status().isOk)
    }

    @Test
    fun `review queue and delta marks update the submission`() {
        val teacher = user(Role.TEACHER, "Class Teacher", "0755010020")
        val student = user(Role.STUDENT, "Alice Mwangi", "0755010021")
        val clazz = teacherClass(teacher)
        val t = token(teacher)
        val s = token(student)

        postExam(
            t,
            exam(
                "exam_review_1", clazz.id.toString(), published = true,
                questions = listOf(
                    question("q_essay", "Explain photosynthesis", "ESSAY", 10, strand = "Writing", key = true, requiresExplanation = true),
                    question("q_mcq", "2 + 2?", "MCQ", 2, options = listOf("3", "4", "5"), correct = "__IDX__1__", strand = "Arithmetic"),
                ),
            ),
        )
        val serverExamId = examRepository.findByClientId("exam_review_1")!!.id
        val session = objectMapper.readValue(
            mockMvc.perform(get("/exams/" + serverExamId + "/session").header("Authorization", auth(s)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            ExamSessionResponse::class.java,
        )
        val essayQid = session.questions.first { it.type == "ESSAY" }.id
        val mcqQid = session.questions.first { it.type == "MCQ" }.id
        val submitBody = objectMapper.writeValueAsString(
            mapOf(essayQid to "Plants make food", mcqQid to "4")
        )
        mockMvc.perform(
            post("/exams/" + serverExamId + "/session/submit").header("Authorization", auth(s))
                .contentType(MediaType.APPLICATION_JSON).content(submitBody)
        ).andExpect(status().isOk)

        val submission = submissionRepository.findByUserIdAndExamId(student.id, serverExamId)!!
        check(submission.score == 2)
        check(submission.percentage == 17)

        val queue = objectMapper.readValue(
            mockMvc.perform(get("/teacher/exams/review-queue").header("Authorization", auth(t)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            Array<TeacherReviewQueueItemPayload>::class.java,
        )
        check(queue.size == 1)
        val item = queue.single()
        check(item.questionId == essayQid)
        check(item.maxMark == 10)
        check(item.suggestedMark == 10)
        check(item.requiresExplanation)
        check(item.studentAnswer == "Plants make food")
        check(item.cbcStrand == "Writing")

        // First mark applies the full award.
        mockMvc.perform(
            post("/teacher/exams/review/" + item.submissionId + "/" + item.questionId)
                .header("Authorization", auth(t))
                .contentType(MediaType.APPLICATION_JSON).content("{\"mark\":6}")
        ).andExpect(status().isNoContent)
        val afterFirst = submissionRepository.findById(UUID.fromString(item.submissionId)).orElseThrow()
        check(afterFirst.score == 8)
        check(afterFirst.percentage == 67)

        // Re-marking applies only the delta, never the whole award again.
        mockMvc.perform(
            post("/teacher/exams/review/" + item.submissionId + "/" + item.questionId)
                .header("Authorization", auth(t))
                .contentType(MediaType.APPLICATION_JSON).content("{\"mark\":4}")
        ).andExpect(status().isNoContent)
        val afterSecond = submissionRepository.findById(UUID.fromString(item.submissionId)).orElseThrow()
        check(afterSecond.score == 6)
        check(afterSecond.percentage == 50)

        // The reviewed mark flows into the student-facing result.
        val result = objectMapper.readValue(
            mockMvc.perform(get("/exams/" + serverExamId + "/result").header("Authorization", auth(s)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            ExamResultPayload::class.java,
        )
        check(result.score == 6)
        check(result.autoGradedScore == 6)
        check(result.pendingReviewScore == 0)
        check(result.markingType == "TEACHER_REVIEW")

        // The reviewed pair disappears from the queue.
        val afterQueue = objectMapper.readValue(
            mockMvc.perform(get("/teacher/exams/review-queue").header("Authorization", auth(t)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            Array<TeacherReviewQueueItemPayload>::class.java,
        )
        check(afterQueue.isEmpty())
    }

    @Test
    fun `analysis reports stable units and key questions`() {
        val teacher = user(Role.TEACHER, "Class Teacher", "0755010030")
        val student = user(Role.STUDENT, "Alice Mwangi", "0755010031")
        val clazz = teacherClass(teacher)
        val t = token(teacher)
        val s = token(student)

        postExam(
            t,
            exam(
                "exam_analysis_1", clazz.id.toString(), published = true,
                questions = listOf(
                    question("q_essay", "Explain photosynthesis", "ESSAY", 10, strand = "Writing", key = true, requiresExplanation = true),
                    question("q_mcq", "2 + 2?", "MCQ", 2, options = listOf("3", "4", "5"), correct = "__IDX__1__", strand = "Arithmetic", key = true),
                ),
            ),
        )
        val serverExamId = examRepository.findByClientId("exam_analysis_1")!!.id
        val session = objectMapper.readValue(
            mockMvc.perform(get("/exams/" + serverExamId + "/session").header("Authorization", auth(s)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            ExamSessionResponse::class.java,
        )
        val essayQid = session.questions.first { it.type == "ESSAY" }.id
        val mcqQid = session.questions.first { it.type == "MCQ" }.id
        mockMvc.perform(
            post("/exams/" + serverExamId + "/session/submit").header("Authorization", auth(s))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(mapOf(essayQid to "Plants", mcqQid to "5")))
        ).andExpect(status().isOk)

        val report = objectMapper.readValue(
            mockMvc.perform(get("/teacher/exams/exam_analysis_1/analysis").header("Authorization", auth(t)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            ExamAnalysisReportPayload::class.java,
        )
        check(report.examId == "exam_analysis_1")
        check(report.className == "Grade 4 South")
        check(report.totalStudents == 1)
        // MCQ wrong -> 0/12.
        check(report.averageScore == 0.0)
        check(report.highestScore == 0)
        check(report.lowestScore == 0)
        check(report.passRate == 0.0)
        check(report.studentPerformances.single().rank == 1)
        check(report.studentPerformances.single().questionBreakdown.size == 2)
        check(report.studentPerformances.single().weakAreas.all { it.masteryLevel in 0.0..1.0 })

        check(report.keyQuestionsSummary.questions.size == 2)
        val mcqStats = report.keyQuestionsSummary.questions.first { it.questionId == mcqQid }
        check(mcqStats.incorrectCount == 1)
        check(mcqStats.correctCount == 0)
        check(mcqStats.masteryPercentage == 0.0)
        check(mcqStats.flaggedByTeacher)

        // Strand mastery is a fraction: Arithmetic 0/2 and Writing 0/10, both critical.
        check(report.weakAreas.any { it.cbcStrand == "Arithmetic" && it.masteryLevel == 0.0 })
        check(report.weakAreas.all { it.masteryLevel in 0.0..1.0 })
        check(report.weakAreas.any { it.severity == "CRITICAL" && it.recommendedAction == "EXTRA_LESSON" })
    }

    @Test
    fun `remediation replaces all and round trips`() {
        val teacher = user(Role.TEACHER, "Class Teacher", "0755010040")
        val other = user(Role.TEACHER, "Other Teacher", "0755010041")
        val clazz = teacherClass(teacher)
        val t = token(teacher)

        postExam(
            t,
            exam(
                "exam_remediation_1", clazz.id.toString(), published = false,
                questions = listOf(question("q1", "Q", "MCQ", 1, options = listOf("a", "b"), correct = "__IDX__0__")),
            ),
        )
        val t2 = token(other)
        val body = objectMapper.writeValueAsString(
            RemediationSavePayload(
                assignments = listOf(
                    RemediationAssignmentPayload("Writing", "EXTRA_LESSON"),
                    RemediationAssignmentPayload("Arithmetic", "PRACTICE_EXERCISES"),
                )
            )
        )
        mockMvc.perform(
            post("/teacher/exams/exam_remediation_1/remediation").header("Authorization", auth(t))
                .contentType(MediaType.APPLICATION_JSON).content(body)
        ).andExpect(status().isNoContent)

        val first = objectMapper.readValue(
            mockMvc.perform(get("/teacher/exams/exam_remediation_1/remediation").header("Authorization", auth(t)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            Array<RemediationAssignmentPayload>::class.java,
        )
        check(first.size == 2)
        check(first.all { it.assignedBy == teacher.id.toString() })

        // Replace-all: the second body is the complete set for the exam.
        mockMvc.perform(
            post("/teacher/exams/exam_remediation_1/remediation").header("Authorization", auth(t))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(RemediationSavePayload(
                    assignments = listOf(RemediationAssignmentPayload("Writing", "MONITOR_ONLY"))
                )))
        ).andExpect(status().isNoContent)
        val second = objectMapper.readValue(
            mockMvc.perform(get("/teacher/exams/exam_remediation_1/remediation").header("Authorization", auth(t)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            Array<RemediationAssignmentPayload>::class.java,
        )
        check(second.size == 1)
        check(second.single().action == "MONITOR_ONLY")

        // Unknown actions are rejected and another teacher is forbidden.
        mockMvc.perform(
            post("/teacher/exams/exam_remediation_1/remediation").header("Authorization", auth(t))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(RemediationSavePayload(
                    assignments = listOf(RemediationAssignmentPayload("Writing", "NOT_A_THING"))
                )))
        ).andExpect(status().isBadRequest)
        mockMvc.perform(
            post("/teacher/exams/exam_remediation_1/remediation").header("Authorization", auth(t2))
                .contentType(MediaType.APPLICATION_JSON).content(body)
        ).andExpect(status().isForbidden)
    }

    @Test
    fun `question bank upserts by client id`() {
        val teacher = user(Role.TEACHER, "Class Teacher", "0755010050")
        val t = token(teacher)
        val bank = TeacherQuestionPayload(
            id = "bank_1",
            examId = "",
            questionText = "Capital of Kenya?",
            questionType = "SHORT_ANSWER",
            points = 3,
            difficulty = 2,
            correctAnswer = "Nairobi",
            cbcStrandTag = "Geography",
        )
        val body = objectMapper.writeValueAsString(bank)
        mockMvc.perform(post("/teacher/question-bank").header("Authorization", auth(t))
            .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isNoContent)
        mockMvc.perform(post("/teacher/question-bank").header("Authorization", auth(t))
            .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isNoContent)

        val listed = objectMapper.readValue(
            mockMvc.perform(get("/teacher/question-bank").header("Authorization", auth(t)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            Array<TeacherQuestionPayload>::class.java,
        )
        check(listed.size == 1)
        check(listed.single().isFromBank)
        check(listed.single().correctAnswer == "Nairobi")
    }
}
