package com.afrithecus.brainbox.api.homework

import com.afrithecus.brainbox.api.auth.web.AuthResponse
import com.afrithecus.brainbox.api.classes.web.CreateClassRequest
import com.afrithecus.brainbox.api.classes.web.TeacherClassPayload
import com.afrithecus.brainbox.api.homework.web.HomeworkPayload
import com.afrithecus.brainbox.api.homework.web.HomeworkUpsertRequest
import com.afrithecus.brainbox.api.homework.web.SubmissionPayload
import com.afrithecus.brainbox.api.traditional.model.ExamTerm
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.model.Role
import com.afrithecus.brainbox.api.identity.repository.UserRepository
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
import java.util.concurrent.atomic.AtomicInteger

/**
 * Homework contract tests: teacher create (idempotent client id), targeted
 * assignment visibility, student submit, grading/return flow, progress.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class HomeworkWebTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val userRepository: UserRepository,
    @Autowired private val passwordEncoder: PasswordEncoder,
) {

    private fun signupStudent(phone: String, schoolName: String): AuthResponse {
        val body = """{"name":"Student ${phone}","phoneNumber":"${phone}","password":"password123","role":"STUDENT","schoolName":"${schoolName}"}"""
        val response = mockMvc.perform(
            post("/auth/signup").header("X-Device-Id", "dev")
                .contentType(MediaType.APPLICATION_JSON).content(body)
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return objectMapper.readValue(response, AuthResponse::class.java)
    }

    private fun newTeacher(schoolId: String, phone: String): AuthResponse {
        val email = phone + "@hw.test"
        val teacher = UserEntity().apply {
            this.phoneNumber = phone
            this.email = email
            passwordHash = passwordEncoder.encode("teacherpass123") ?: error("encode")
            name = "HW Teacher"
            role = Role.TEACHER
            this.schoolId = UUID.fromString(schoolId)
            isActive = true
            isVerified = true
        }
        userRepository.save(teacher)
        val login = mockMvc.perform(
            post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("""{"identifier":"${email}","password":"teacherpass123"}""")
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return objectMapper.readValue(login, AuthResponse::class.java)
    }

    private fun auth(token: String) = "Bearer " + token

    private fun createClass(teacher: AuthResponse, students: List<AuthResponse>): String {
        val req = CreateClassRequest(name = "HW Class", grade = "Form 3", subject = "MATHEMATICS")
        val body = objectMapper.writeValueAsString(req)
        val resp = mockMvc.perform(
            post("/teacher/classes").header("Authorization", auth(teacher.sessionToken!!))
                .contentType(MediaType.APPLICATION_JSON).content(body)
        ).andExpect(status().isOk).andReturn().response.contentAsString
        val clazz = objectMapper.readValue(resp, TeacherClassPayload::class.java)
        mockMvc.perform(
            post("/teacher/classes/${clazz.classId}/students").header("Authorization", auth(teacher.sessionToken!!))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"studentIds":[${students.joinToString { "\"" + it.user.id + "\"" }}]}""")
        ).andExpect(status().isNoContent)
        return clazz.classId
    }

    private fun hwRequest(
        id: String,
        classId: String,
        title: String = "Solve exercises 1-5",
        assigned: List<String>? = null,
        checklist: List<String>? = null,
        type: String = "FREE_TEXT",
        isDraft: Boolean = false,
        term: ExamTerm? = null,
    ) = HomeworkUpsertRequest(
        id = id, classId = classId, title = title,
        description = "Complete the following textbook exercises and show all working.",
        subject = "MATHEMATICS", gradeLevel = 3, term = term,
        dueDate = System.currentTimeMillis() + 24L * 3600 * 1000,
        submissionType = type, checklistItems = checklist,
        gradingMode = "MANUAL", assignedStudentIds = assigned,
        isDraft = isDraft,
    )

    private fun upsert(teacher: AuthResponse, request: HomeworkUpsertRequest): String {
        val body = objectMapper.writeValueAsString(request)
        return mockMvc.perform(
            post("/teacher/homework").header("Authorization", auth(teacher.sessionToken!!))
                .contentType(MediaType.APPLICATION_JSON).content(body)
        ).andExpect(status().isOk).andReturn().response.contentAsString
    }

    @Test
    fun `homework targets students and runs submit grade return resubmit`() {
        val host = signupStudent("0775000001", "HW High")
        val teacher = newTeacher(host.user.schoolId!!, "0775777001")
        val student1 = signupStudent("0775000002", "HW High")
        val student2 = signupStudent("0775000003", "HW High")
        val classId = createClass(teacher, listOf(student1, student2))

        // create is an idempotent upsert keyed on the client id
        val request = hwRequest("hw_12345", classId, assigned = listOf(student1.user.id))
        val first = upsert(teacher, request)
        val second = upsert(teacher, request)
        check(objectMapper.readValue(first, HomeworkPayload::class.java).id == "hw_12345")
        check(second == first)

        // targeted student sees it; the other does not
        val mine1 = mockMvc.perform(
            get("/homework").header("Authorization", auth(student1.sessionToken!!))
        ).andExpect(status().isOk).andReturn().response.contentAsString
        check(objectMapper.readValue(mine1, Array<HomeworkPayload>::class.java).any { it.id == "hw_12345" })

        val mine2 = mockMvc.perform(
            get("/homework").header("Authorization", auth(student2.sessionToken!!))
        ).andExpect(status().isOk).andReturn().response.contentAsString
        check(objectMapper.readValue(mine2, Array<HomeworkPayload>::class.java).none { it.id == "hw_12345" })

        // submit -> pending
        mockMvc.perform(
            post("/homework/hw_12345/submit").header("Authorization", auth(student1.sessionToken!!))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"submissionText":"x=2, y=5"}""")
        ).andExpect(status().isOk)

        // teacher sees the submission and grades it
        val subs = mockMvc.perform(
            get("/teacher/homework/hw_12345/submissions").header("Authorization", auth(teacher.sessionToken!!))
        ).andExpect(status().isOk).andReturn().response.contentAsString
        val list = objectMapper.readValue(subs, Array<SubmissionPayload>::class.java)
        check(list.size == 1)
        check(list.single().status == "PENDING")
        val submissionId = list.single().id

        mockMvc.perform(
            post("/teacher/homework/grade/${submissionId}").header("Authorization", auth(teacher.sessionToken!!))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"grade":85,"feedback":"Good working"}""")
        ).andExpect(status().isOk)

        // student sees the grade; resubmission while graded is rejected
        val detail = mockMvc.perform(
            get("/homework/hw_12345").header("Authorization", auth(student1.sessionToken!!))
        ).andExpect(status().isOk).andReturn().response.contentAsString
        val payload = objectMapper.readValue(detail, HomeworkPayload::class.java)
        check(payload.submissionStatus == "GRADED")
        check(payload.grade == 85)

        mockMvc.perform(
            post("/homework/hw_12345/submit").header("Authorization", auth(student1.sessionToken!!))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"submissionText":"updated"}""")
        ).andExpect(status().isConflict)

        // teacher returns it -> ungraded, resubmission allowed
        mockMvc.perform(
            post("/teacher/homework/return/${submissionId}").header("Authorization", auth(teacher.sessionToken!!))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"feedback":"Redo Q3"}""")
        ).andExpect(status().isOk)
        mockMvc.perform(
            post("/homework/hw_12345/submit").header("Authorization", auth(student1.sessionToken!!))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"submissionText":"updated again"}""")
        ).andExpect(status().isOk)

        // progress endpoint
        val progress = mockMvc.perform(
            get("/teacher/homework/progress?ids=hw_12345").header("Authorization", auth(teacher.sessionToken!!))
        ).andExpect(status().isOk).andReturn().response.contentAsString
        val items = objectMapper.readValue(progress, Array<com.afrithecus.brainbox.api.homework.web.HomeworkProgressItem>::class.java)
        check(items.single().total == 1)
        check(items.single().pending == 1)
    }

    @Test
    fun `drafts stay hidden and checklist homework validates`() {
        val host = signupStudent("0775000004", "HW High")
        val teacher = newTeacher(host.user.schoolId!!, "0775777002")
        val student = signupStudent("0775000005", "HW High")
        val classId = createClass(teacher, listOf(student))

        // draft is not visible to students
        val draft = hwRequest("hw_draft_1", classId, isDraft = true)
        val draftBody = upsert(teacher, draft)
        check(objectMapper.readValue(draftBody, HomeworkPayload::class.java).isDraft)
        val mine = mockMvc.perform(
            get("/homework").header("Authorization", auth(student.sessionToken!!))
        ).andExpect(status().isOk).andReturn().response.contentAsString
        check(objectMapper.readValue(mine, Array<HomeworkPayload>::class.java).none { it.id == "hw_draft_1" })

        // checklist with no items is rejected
        val bad = hwRequest("hw_bad_1", classId, type = "CHECKLIST", checklist = emptyList())
        mockMvc.perform(
            post("/teacher/homework").header("Authorization", auth(teacher.sessionToken!!))
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(bad))
        ).andExpect(status().isBadRequest)

        // archive via PUT honors isActive=false (soft delete)
        val created = hwRequest("hw_archive_1", classId, assigned = listOf(student.user.id))
        upsert(teacher, created)
        val archived = created.copy(isActive = false)
        mockMvc.perform(
            put("/teacher/homework/hw_archive_1").header("Authorization", auth(teacher.sessionToken!!))
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(archived))
        ).andExpect(status().isOk)
        val after = mockMvc.perform(
            get("/homework").header("Authorization", auth(student.sessionToken!!))
        ).andExpect(status().isOk).andReturn().response.contentAsString
        check(objectMapper.readValue(after, Array<HomeworkPayload>::class.java).none { it.id == "hw_archive_1" })
    }

    private fun qsetRequest(
        id: String,
        classId: String,
        mode: String,
        studentId: String,
        dueAgoMs: Long = 0L,
    ): HomeworkUpsertRequest = HomeworkUpsertRequest(
        id = id, classId = classId, title = "Question set practice",
        description = "Answer the auto-graded questions below and check your score.",
        subject = "MATHEMATICS", gradeLevel = 3,
        dueDate = System.currentTimeMillis() + (24L * 3600 * 1000) - dueAgoMs,
        submissionType = "EXAM_QUESTION_SET", gradingMode = mode,
        assignedStudentIds = listOf(studentId),
        questions = listOf(
            com.afrithecus.brainbox.api.homework.web.HomeworkQuestionRequest(
                text = "2+2?", type = "MCQ", options = listOf("3", "4"), correctAnswer = "4", points = 2,
            ),
            com.afrithecus.brainbox.api.homework.web.HomeworkQuestionRequest(
                text = "3+3?", type = "MCQ", options = listOf("5", "6"), correctAnswer = "6", points = 3,
            ),
            com.afrithecus.brainbox.api.homework.web.HomeworkQuestionRequest(
                text = "1+1?", type = "MCQ", options = listOf("2", "3"), correctAnswer = "2", points = 1,
            ),
        ),
    )

    @Test
    fun `question set auto grades immediately and post completion`() {
        val host = signupStudent("0775000010", "HW High")
        val teacher = newTeacher(host.user.schoolId!!, "0775777003")
        val student = signupStudent("0775000011", "HW High")
        val classId = createClass(teacher, listOf(student))

        // AUTO_IMMEDIATE
        val immediate = qsetRequest("hw_q1", classId, "AUTO_IMMEDIATE", student.user.id)
        val createdBody = upsert(teacher, immediate)
        val created = objectMapper.readValue(createdBody, HomeworkPayload::class.java)
        check(created.questions?.size == 3)
        check(createdBody.contains("correctAnswer")) // teacher keeps keys

        // student detail strips keys
        val studentDetailRaw = mockMvc.perform(
            get("/homework/hw_q1").header("Authorization", auth(student.sessionToken!!))
        ).andExpect(status().isOk).andReturn().response.contentAsString
        check(!studentDetailRaw.contains("correctAnswer"))
        check(studentDetailRaw.contains("2+2?"))
        val studentDetail = objectMapper.readValue(studentDetailRaw, HomeworkPayload::class.java)
        check(studentDetail.questions?.size == 3)

        // answers reference the real question ids; all correct -> 100%
        val answers = mutableMapOf<String, String>()
        studentDetail.questions!!.forEach { answers[it.id] = if (it.text.startsWith("2+2")) "4" else if (it.text.startsWith("3+3")) "6" else "2" }
        val submit2 = mockMvc.perform(
            post("/homework/hw_q1/submit").header("Authorization", auth(student.sessionToken!!))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"answers":${objectMapper.writeValueAsString(answers)}}""")
        ).andExpect(status().isOk).andReturn().response.contentAsString
        val after = objectMapper.readValue(submit2, HomeworkPayload::class.java)
        check(after.submissionStatus == "GRADED")
        check(after.grade == 100)

        // AUTO_POST_COMPLETION with a past due date reveals on read; future stays hidden
        val pastDue = qsetRequest("hw_q2", classId, "AUTO_POST_COMPLETION", student.user.id, dueAgoMs = 48L * 3600 * 1000)
        upsert(teacher, pastDue)
        val answersWrong = mutableMapOf<String, String>()
        val q2detail = objectMapper.readValue(
            mockMvc.perform(get("/homework/hw_q2").header("Authorization", auth(student.sessionToken!!)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            HomeworkPayload::class.java,
        )
        q2detail.questions!!.forEach { answersWrong[it.id] = "wrong" }
        mockMvc.perform(
            post("/homework/hw_q2/submit").header("Authorization", auth(student.sessionToken!!))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"answers":${objectMapper.writeValueAsString(answersWrong)}}""")
        ).andExpect(status().isOk)
        val revealed = mockMvc.perform(
            get("/homework/hw_q2").header("Authorization", auth(student.sessionToken!!))
        ).andExpect(status().isOk).andReturn().response.contentAsString
        val revealedPayload = objectMapper.readValue(revealed, HomeworkPayload::class.java)
        check(revealedPayload.submissionStatus == "GRADED")
        check(revealedPayload.grade == 0)
    }

    @Test
    fun `homework echoes an explicit term and defaults from the creation month`() {
        val host = signupStudent("0775000020", "HW High")
        val teacher = newTeacher(host.user.schoolId!!, "0775777004")
        val student = signupStudent("0775000021", "HW High")
        val classId = createClass(teacher, listOf(student))

        val explicit = objectMapper.readValue(
            upsert(teacher, hwRequest("hw_t1", classId, term = ExamTerm.TERM_2)),
            HomeworkPayload::class.java,
        )
        check(explicit.term == ExamTerm.TERM_2)

        val derived = objectMapper.readValue(
            upsert(teacher, hwRequest("hw_t2", classId)),
            HomeworkPayload::class.java,
        )
        val expected = when (java.time.LocalDate.now(java.time.ZoneOffset.UTC).monthValue) {
            1, 2, 3, 4 -> ExamTerm.TERM_1
            5, 6, 7, 8 -> ExamTerm.TERM_2
            else -> ExamTerm.TERM_3
        }
        check(derived.term == expected)
    }

    private companion object {
        val nextTeacher = AtomicInteger(0)
    }
}

