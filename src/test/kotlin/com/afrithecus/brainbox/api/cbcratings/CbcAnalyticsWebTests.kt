package com.afrithecus.brainbox.api.cbcratings

import com.afrithecus.brainbox.api.auth.web.AuthResponse
import com.afrithecus.brainbox.api.cbcratings.web.CbcClassReportPayload
import com.afrithecus.brainbox.api.cbcratings.web.CbcCurriculumMapPayload
import com.afrithecus.brainbox.api.cbcratings.web.CbcReportCardPayload
import com.afrithecus.brainbox.api.cbcratings.web.StrandMasteryDetailPayload
import com.afrithecus.brainbox.api.classes.entity.ClassMembershipEntity
import com.afrithecus.brainbox.api.classes.entity.TeacherClassEntity
import com.afrithecus.brainbox.api.classes.repository.ClassMembershipRepository
import com.afrithecus.brainbox.api.classes.repository.TeacherClassRepository
import com.afrithecus.brainbox.api.identity.entity.SchoolEntity
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.model.Role
import com.afrithecus.brainbox.api.identity.repository.SchoolRepository
import com.afrithecus.brainbox.api.identity.repository.UserRepository
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
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper

/** Teacher CBC analytics and ratings (doc 04 CBC analytics). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class CbcAnalyticsWebTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val userRepository: UserRepository,
    @Autowired private val schoolRepository: SchoolRepository,
    @Autowired private val classRepository: TeacherClassRepository,
    @Autowired private val membershipRepository: ClassMembershipRepository,
    @Autowired private val passwordEncoder: PasswordEncoder,
) {
    private fun user(role: Role, name: String, phone: String, schoolId: java.util.UUID): UserEntity =
        userRepository.save(UserEntity().apply {
            phoneNumber = phone
            email = phone + "@cbc.test"
            passwordHash = passwordEncoder.encode("password123") ?: error("encode")
            this.name = name
            this.role = role
            this.schoolId = schoolId
            gradeLevel = if (role == Role.STUDENT) "Grade 4" else null
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
    fun `cbc ratings, class report, student card and strand detail`() {
        val school = SchoolEntity().apply {
            name = "Alliance High School"
            isActive = true
        }
        schoolRepository.save(school)
        val teacher = user(Role.TEACHER, "Class Teacher", "0755090001", school.id)
        val other = user(Role.TEACHER, "Other Teacher", "0755090002", school.id)
        val student = user(Role.STUDENT, "Alice Learner", "0755090003", school.id)
        val clazz = classRepository.save(TeacherClassEntity().apply {
            teacherUserId = teacher.id
            this.schoolId = school.id
            name = "Grade 4 South"
            gradeLevel = "Grade 4"
            subject = "Mathematics"
            isActive = true
        })
        membershipRepository.save(ClassMembershipEntity().apply {
            classId = clazz.id
            studentId = student.id
        })
        val t = token(teacher)

        val map = objectMapper.readValue(
            mockMvc.perform(get("/teacher/cbc/curriculum-map").header("Authorization", auth(t)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            CbcCurriculumMapPayload::class.java,
        )
        check(map.strands.size >= 15)
        check(map.strands.any { it.code == "MAT-NUM" })

        // Rate, then re-rate the same pair: it must correct, not duplicate.
        mockMvc.perform(post("/teacher/cbc/rating")
            .param("studentId", student.id.toString()).param("strandCode", "MAT-NUM")
            .param("term", "TERM_1").param("rating", "MEETING").param("comments", "Improving")
            .header("Authorization", auth(t)))
            .andExpect(status().isNoContent)
        mockMvc.perform(post("/teacher/cbc/rating")
            .param("studentId", student.id.toString()).param("strandCode", "MAT-NUM")
            .param("term", "TERM_1").param("rating", "EXCEEDING")
            .header("Authorization", auth(t)))
            .andExpect(status().isNoContent)
        mockMvc.perform(post("/teacher/cbc/rating")
            .param("studentId", student.id.toString()).param("strandCode", "ENG-READ")
            .param("term", "TERM_1").param("rating", "BELOW")
            .header("Authorization", auth(t)))
            .andExpect(status().isNoContent)

        val report = objectMapper.readValue(
            mockMvc.perform(get("/teacher/cbc/analytics?classId=" + clazz.id + "&term=TERM_1")
                .header("Authorization", auth(t)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            CbcClassReportPayload::class.java,
        )
        check(report.className == "Grade 4 South")
        check(report.strandMastery.size == 2)
        check(report.overallClassAverage > 0)
        check(report.subjectTeacherPerformance.single().subject == "Mathematics")
        check(report.strandMastery.any { it.isWeakStrand && it.strandCode == "ENG-READ" })

        val card = objectMapper.readValue(
            mockMvc.perform(get("/teacher/cbc/student/" + student.id + "?term=TERM_1")
                .header("Authorization", auth(t)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            CbcReportCardPayload::class.java,
        )
        check(card.studentName == "Alice Learner")
        check(card.strandRatings.size == 2)
        check(card.strandRatings.any { it.rating == "EXCEEDING" })
        check(card.gradeLevel == "Grade 4")

        val detail = objectMapper.readValue(
            mockMvc.perform(get("/teacher/cbc/strand/MAT-NUM?classId=" + clazz.id)
                .header("Authorization", auth(t)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            StrandMasteryDetailPayload::class.java,
        )
        check(detail.strandCode == "MAT-NUM")
        check(detail.classPerformance["EXCEEDING"] == 1)
        check(detail.studentRatings.single().studentName == "Alice Learner")

        // A teacher who does not share the class cannot read it.
        mockMvc.perform(get("/teacher/cbc/analytics?classId=" + clazz.id + "&term=TERM_1")
            .header("Authorization", auth(token(other))))
            .andExpect(status().isForbidden)
    }
}
