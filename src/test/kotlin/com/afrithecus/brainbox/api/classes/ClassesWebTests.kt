package com.afrithecus.brainbox.api.classes

import com.afrithecus.brainbox.api.auth.web.AuthResponse
import com.afrithecus.brainbox.api.classes.web.CreateClassRequest
import com.afrithecus.brainbox.api.classes.web.TeacherClassPayload
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.model.Role
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import com.afrithecus.brainbox.api.auth.web.UserPayload
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
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.util.concurrent.atomic.AtomicInteger

/**
 * Teacher classes & roster + student class view (doc 04 §2.2).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ClassesWebTests(
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

    /** Creates a teacher at the given school and logs them in. */
    private fun newTeacher(schoolId: String, phone: String): AuthResponse {
        val email = phone + "@teacher.test"
        val teacher = UserEntity().apply {
            this.phoneNumber = phone
            this.email = email
            passwordHash = passwordEncoder.encode("teacherpass123") ?: error("encode")
            name = "Teacher " + phone
            role = Role.TEACHER
            this.schoolId = java.util.UUID.fromString(schoolId)
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

    private fun createClass(teacher: AuthResponse, name: String): TeacherClassPayload {
        val request = CreateClassRequest(name = name, grade = "Form 3", subject = "MATHEMATICS")
        val body = objectMapper.writeValueAsString(request)
        val response = mockMvc.perform(
            post("/teacher/classes").header("Authorization", auth(teacher.sessionToken!!))
                .contentType(MediaType.APPLICATION_JSON).content(body)
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return objectMapper.readValue(response, TeacherClassPayload::class.java)
    }

    @Test
    fun `teacher creates classes, owns rosters and other teachers are forbidden`() {
        val schoolOwner = signupStudent("0774000001", "Class High")
        val schoolId = schoolOwner.user.schoolId!!
        val teacherA = newTeacher(schoolId, "0774555001")
        val teacherB = newTeacher(schoolId, "0774555002")
        val student1 = signupStudent("0774000002", "Class High")
        val student2 = signupStudent("0774000003", "Class High")

        val clazz = createClass(teacherA, "Maths 3A")
        check(clazz.studentCount == 0)

        // teacher A adds both students to the roster
        mockMvc.perform(
            post("/teacher/classes/${clazz.classId}/students").header("Authorization", auth(teacherA.sessionToken!!))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"studentIds":["${student1.user.id}","${student2.user.id}"]}""")
        ).andExpect(status().isNoContent)

        val roster = mockMvc.perform(
            get("/teacher/classes/${clazz.classId}/students").header("Authorization", auth(teacherA.sessionToken!!))
        ).andExpect(status().isOk).andReturn().response.contentAsString
        val students = objectMapper.readValue(roster, Array<com.afrithecus.brainbox.api.classes.web.StudentInClassPayload>::class.java)
        check(students.size == 2)
        check(students.all { it.name.isNotBlank() })

        val myClasses = mockMvc.perform(
            get("/classes/my").header("Authorization", auth(student1.sessionToken!!))
        ).andExpect(status().isOk).andReturn().response.contentAsString
        val mine = objectMapper.readValue(myClasses, Array<TeacherClassPayload>::class.java)
        check(mine.any { it.classId == clazz.classId })
        check(mine.single().studentCount == 2)

        // teacher B cannot touch teacher A's class
        mockMvc.perform(
            get("/teacher/classes/${clazz.classId}/students").header("Authorization", auth(teacherB.sessionToken!!))
        ).andExpect(status().isForbidden)

        val bClasses = mockMvc.perform(
            get("/teacher/classes").header("Authorization", auth(teacherB.sessionToken!!))
        ).andExpect(status().isOk).andReturn().response.contentAsString
        check(objectMapper.readValue(bClasses, Array<TeacherClassPayload>::class.java).isEmpty())

        // removing a student empties their class view
        mockMvc.perform(
            delete("/teacher/classes/${clazz.classId}/students/${student2.user.id}")
                .header("Authorization", auth(teacherA.sessionToken!!))
        ).andExpect(status().isNoContent)
        val mineAfter = mockMvc.perform(
            get("/classes/my").header("Authorization", auth(student2.sessionToken!!))
        ).andExpect(status().isOk).andReturn().response.contentAsString
        check(objectMapper.readValue(mineAfter, Array<TeacherClassPayload>::class.java).none { it.classId == clazz.classId })
    }

    @Test
    fun `students from another school cannot be rostered`() {
        val host = signupStudent("0774000004", "Host High")
        val outsider = signupStudent("0774000005", "Other High")
        val teacher = newTeacher(host.user.schoolId!!, "0774555003")
        val clazz = createClass(teacher, "Host Class")

        mockMvc.perform(
            post("/teacher/classes/${clazz.classId}/students").header("Authorization", auth(teacher.sessionToken!!))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"studentIds":["${outsider.user.id}"]}""")
        ).andExpect(status().isBadRequest)
    }
}
