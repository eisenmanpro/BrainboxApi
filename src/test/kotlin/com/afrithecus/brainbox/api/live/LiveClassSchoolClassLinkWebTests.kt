package com.afrithecus.brainbox.api.live

import com.afrithecus.brainbox.api.auth.web.AuthResponse
import com.afrithecus.brainbox.api.classes.entity.TeacherClassEntity
import com.afrithecus.brainbox.api.classes.repository.TeacherClassRepository
import com.afrithecus.brainbox.api.identity.entity.SchoolEntity
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.model.Role
import com.afrithecus.brainbox.api.identity.repository.SchoolRepository
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import com.afrithecus.brainbox.api.live.web.LiveClassPayload
import com.afrithecus.brainbox.api.live.web.LiveClassSettingsPayload
import com.afrithecus.brainbox.api.live.web.TeacherLiveClassRequest
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

/**
 * The web-only school-class link on a live class
 * (`classId` on create/update and on the payload): the schedule form picks one of the
 * teacher's classes and the attendance auto-mark filter reads it back from
 * `GET /teacher/live-classes`. Android's model has no such field, so it must stay optional
 * and be refused when it points at another school's class.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class LiveClassSchoolClassLinkWebTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val userRepository: UserRepository,
    @Autowired private val schoolRepository: SchoolRepository,
    @Autowired private val classRepository: TeacherClassRepository,
    @Autowired private val passwordEncoder: PasswordEncoder,
) {

    private lateinit var school: SchoolEntity
    private lateinit var otherSchool: SchoolEntity
    private lateinit var teacher: UserEntity
    private lateinit var ownClass: TeacherClassEntity
    private lateinit var foreignClass: TeacherClassEntity

    private fun user(name: String, phone: String, schoolId: java.util.UUID): UserEntity =
        userRepository.save(UserEntity().apply {
            this.name = name
            phoneNumber = phone
            email = phone + "@liveclass.test"
            passwordHash = passwordEncoder.encode("password123") ?: error("encode")
            role = Role.TEACHER
            this.schoolId = schoolId
            isActive = true
            isVerified = true
        })

    private fun token(entity: UserEntity): String {
        val body = mockMvc.perform(
            post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"identifier\":\"" + entity.phoneNumber + "\",\"password\":\"password123\"}")
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return objectMapper.readValue(body, AuthResponse::class.java).sessionToken!!
    }

    private fun request(classId: String?): TeacherLiveClassRequest {
        val start = System.currentTimeMillis() + 3_600_000
        return TeacherLiveClassRequest(
            id = "lc_class_link_1",
            title = "Fractions Live",
            subject = "Mathematics",
            description = "Live class linked to a school class",
            classId = classId,
            scheduledStart = start,
            scheduledEnd = start + 3_600_000,
            settings = LiveClassSettingsPayload(visibility = "CLASS_ONLY"),
        )
    }

    private fun create(body: String, token: String): LiveClassPayload {
        val content = mockMvc.perform(
            post("/teacher/live-classes").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(body)
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return objectMapper.readValue(content, LiveClassPayload::class.java)
    }

    @BeforeEach
    fun setUp() {
        school = schoolRepository.save(SchoolEntity().apply { name = "Alliance High School"; isActive = true })
        otherSchool = schoolRepository.save(SchoolEntity().apply { name = "Moi Avenue School"; isActive = true })
        teacher = user("Class Teacher", "0701000001", school.id)
        ownClass = classRepository.save(TeacherClassEntity().apply {
            teacherUserId = teacher.id
            schoolId = school.id
            name = "Grade 7 West"
            gradeLevel = "Grade 7"
            subject = "Mathematics"
            isActive = true
        })
        foreignClass = classRepository.save(TeacherClassEntity().apply {
            teacherUserId = teacher.id
            schoolId = otherSchool.id
            name = "Grade 7 Other"
            gradeLevel = "Grade 7"
            subject = "Mathematics"
            isActive = true
        })
    }

    @Test
    fun `a live class created with a school class exposes it on the payload and keeps it on edit`() {
        val t = token(teacher)
        val created = create(objectMapper.writeValueAsString(request(ownClass.id.toString())), t)
        check(created.classId == ownClass.id.toString()) { "classId=" + created.classId }

        val listedBody = mockMvc.perform(get("/teacher/live-classes").header("Authorization", "Bearer " + t))
            .andExpect(status().isOk).andReturn().response.contentAsString
        val listed = objectMapper.readValue(listedBody, Array<LiveClassPayload>::class.java).toList()
        check(listed.size == 1 && listed.single().classId == ownClass.id.toString())

        // An edit that does not repeat the field (Android's model) must not unlink it.
        val updatedBody = mockMvc.perform(
            put("/teacher/live-classes/" + created.id).header("Authorization", "Bearer " + t)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request(null)))
        ).andExpect(status().isOk).andReturn().response.contentAsString
        val updated = objectMapper.readValue(updatedBody, LiveClassPayload::class.java)
        check(updated.classId == ownClass.id.toString()) { "edit cleared classId: " + updated.classId }
    }

    @Test
    fun `a class from another school is refused`() {
        mockMvc.perform(
            post("/teacher/live-classes").header("Authorization", "Bearer " + token(teacher))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request(foreignClass.id.toString())))
        ).andExpect(status().isForbidden)
    }

    @Test
    fun `a malformed class id is a bad request`() {
        mockMvc.perform(
            post("/teacher/live-classes").header("Authorization", "Bearer " + token(teacher))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request("not-a-uuid")))
        ).andExpect(status().isBadRequest)
    }
}
