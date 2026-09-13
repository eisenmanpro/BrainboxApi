package com.afrithecus.brainbox.api.teacher

import com.afrithecus.brainbox.api.auth.web.AuthResponse
import com.afrithecus.brainbox.api.classes.entity.TeacherClassEntity
import com.afrithecus.brainbox.api.classes.repository.TeacherClassRepository
import com.afrithecus.brainbox.api.identity.entity.SchoolEntity
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.model.Role
import com.afrithecus.brainbox.api.identity.repository.SchoolRepository
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import com.afrithecus.brainbox.api.teacher.web.TeacherProfilePayload
import com.afrithecus.brainbox.api.teacher.web.TeacherSettingsPayload
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
import java.util.UUID

/** Teacher settings and profile (doc 04). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class TeacherSettingsWebTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val userRepository: UserRepository,
    @Autowired private val schoolRepository: SchoolRepository,
    @Autowired private val classRepository: TeacherClassRepository,
    @Autowired private val passwordEncoder: PasswordEncoder,
) {
    private fun teacher(phone: String, schoolId: UUID): UserEntity = userRepository.save(UserEntity().apply {
        phoneNumber = phone
        email = phone + "@settings.test"
        passwordHash = passwordEncoder.encode("password123") ?: error("encode")
        name = "Class Teacher"
        role = Role.TEACHER
        this.schoolId = schoolId
        isActive = true
        isVerified = true
    })

    private fun token(user: UserEntity): String {
        val body = mockMvc.perform(
            post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"identifier\":\"" + user.email + "\",\"password\":\"password123\"}")
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return objectMapper.readValue(body, AuthResponse::class.java).sessionToken!!
    }

    private fun auth(token: String) = "Bearer " + token

    @Test
    fun `settings and profile round trip`() {
        val school = SchoolEntity().apply {
            name = "Alliance High School"
            isActive = true
        }
        schoolRepository.save(school)
        val teacher = teacher("0755080001", school.id)
        val student = userRepository.save(UserEntity().apply {
            phoneNumber = "0755080002"
            email = "0755080002@settings.test"
            passwordHash = passwordEncoder.encode("password123") ?: error("encode")
            name = "Learner"
            role = Role.STUDENT
            this.schoolId = school.id
            isActive = true
            isVerified = true
        })
        classRepository.save(TeacherClassEntity().apply {
            teacherUserId = teacher.id
            this.schoolId = school.id
            name = "Grade 4 South"
            gradeLevel = "Grade 4"
            subject = "Mathematics"
            isActive = true
        })
        val t = token(teacher)

        val defaults = objectMapper.readValue(
            mockMvc.perform(get("/teacher/settings").header("Authorization", auth(t)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            TeacherSettingsPayload::class.java,
        )
        check(defaults.teacherId == teacher.id.toString())
        check(defaults.name == "Class Teacher")
        check(defaults.schoolName == "Alliance High School")
        check(defaults.languagePreference == "en")
        check(defaults.notificationPreferences.parentMessages)

        val updated = objectMapper.readValue(
            mockMvc.perform(put("/teacher/settings").header("Authorization", auth(t))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(defaults.copy(
                    name = "Senior Teacher",
                    subjectsTaught = listOf("Mathematics", "Physics"),
                    tscNumber = "TSC-123",
                    themePreference = "light",
                    autoAttendance = false,
                    defaultGradeWeighting = mapOf("EXAM" to 0.5, "HOMEWORK" to 0.5),
                    notificationPreferences = defaults.notificationPreferences.copy(emailNotifications = true),
                ))))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            TeacherSettingsPayload::class.java,
        )
        check(updated.name == "Senior Teacher")
        check(updated.subjectsTaught == listOf("Mathematics", "Physics"))
        check(updated.tscNumber == "TSC-123")
        check(updated.autoAttendance.not())
        check(updated.defaultGradeWeighting["EXAM"] == 0.5)
        check(updated.notificationPreferences.emailNotifications)

        val reread = objectMapper.readValue(
            mockMvc.perform(get("/teacher/settings").header("Authorization", auth(t)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            TeacherSettingsPayload::class.java,
        )
        check(reread.themePreference == "light")
        check(reread.name == "Senior Teacher")

        val profile = objectMapper.readValue(
            mockMvc.perform(get("/teacher/profile").header("Authorization", auth(t)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            TeacherProfilePayload::class.java,
        )
        check(profile.id == teacher.id.toString())
        check(profile.role == "TEACHER")
        check(profile.schoolName == "Alliance High School")
        check(profile.className == "Grade 4 South")
        check(profile.subjects?.contains("Mathematics") == true)
        check(profile.verificationStatus == "VERIFIED")

        // Profile update persists the display name and subjects.
        val changed = objectMapper.readValue(
            mockMvc.perform(put("/teacher/profile").header("Authorization", auth(t))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(profile.copy(
                    name = "Head of Maths",
                    subjects = listOf("Mathematics"),
                    tscNumber = "TSC-999",
                ))))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            TeacherProfilePayload::class.java,
        )
        check(changed.name == "Head of Maths")

        mockMvc.perform(get("/teacher/settings").header("Authorization", auth(token(student))))
            .andExpect(status().isForbidden)
    }
}
