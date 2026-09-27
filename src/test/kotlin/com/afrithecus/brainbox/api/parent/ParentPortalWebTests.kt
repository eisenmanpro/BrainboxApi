package com.afrithecus.brainbox.api.parent

import com.afrithecus.brainbox.api.auth.web.AuthResponse
import com.afrithecus.brainbox.api.identity.entity.SchoolEntity
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.model.Role
import com.afrithecus.brainbox.api.identity.repository.SchoolRepository
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import com.afrithecus.brainbox.api.parent.web.LinkedChildPayload
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
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * The parent portal reads and writes the `users.parent_user_id` link every other parent
 * surface authorizes against, so this test pins the link rules: a guardian links their own
 * school's learner by admission number, cannot steal another guardian's link, cannot reach
 * into another school, and the child list follows the link.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ParentPortalWebTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val users: UserRepository,
    @Autowired private val schools: SchoolRepository,
    @Autowired private val passwordEncoder: PasswordEncoder,
) {

    private val sequence = AtomicInteger(0)

    @Test
    fun `a guardian links a learner by admission number and sees them listed`() {
        val school = school()
        val otherSchool = school()
        val parentToken = token(account(Role.PARENT, "Guardian One", school.id))
        val child = account(Role.STUDENT, "Pupil One", school.id, admission = "ADM-P1-" + suffix())
        val foreign = account(Role.STUDENT, "Pupil Elsewhere", otherSchool.id, admission = "ADM-P2-" + suffix())

        // Nothing linked yet.
        check(children(parentToken).isEmpty())

        // A learner in another school is not claimable, and is answered as unknown.
        mockMvc.perform(
            post("/parent/child/link").header("Authorization", auth(parentToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"admissionNumber":"${foreign.studentAdmissionNumber}"}""")
        ).andExpect(status().isNotFound)

        val linked = objectMapper.readValue(
            mockMvc.perform(
                post("/parent/child/link").header("Authorization", auth(parentToken))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"admissionNumber":"${child.studentAdmissionNumber}"}""")
            ).andExpect(status().isOk).andReturn().response.contentAsString,
            LinkedChildPayload::class.java,
        )
        check(linked.id == child.id.toString())
        check(linked.name == "Pupil One")
        check(linked.grade == "Grade 6")

        // Replaying the link is idempotent and the link is now visible.
        mockMvc.perform(
            post("/parent/child/link").header("Authorization", auth(parentToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"admissionNumber":"${child.studentAdmissionNumber}"}""")
        ).andExpect(status().isOk)
        check(children(parentToken).map { it.id } == listOf(child.id.toString()))

        // Unlinking removes it from the list and is repeatable-safe via 404.
        mockMvc.perform(delete("/parent/child/${child.id}").header("Authorization", auth(parentToken)))
            .andExpect(status().isNoContent)
        check(children(parentToken).isEmpty())
        mockMvc.perform(delete("/parent/child/${child.id}").header("Authorization", auth(parentToken)))
            .andExpect(status().isNotFound)
    }

    @Test
    fun `a learner with a guardian of record cannot be claimed twice`() {
        val school = school()
        val firstGuardian = token(account(Role.PARENT, "Guardian A", school.id))
        val secondGuardian = token(account(Role.PARENT, "Guardian B", school.id))
        val child = account(Role.STUDENT, "Pupil Two", school.id, admission = "ADM-P3-" + suffix())

        mockMvc.perform(
            post("/parent/child/link").header("Authorization", auth(firstGuardian))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"admissionNumber":"${child.studentAdmissionNumber}"}""")
        ).andExpect(status().isOk)

        mockMvc.perform(
            post("/parent/child/link").header("Authorization", auth(secondGuardian))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"admissionNumber":"${child.studentAdmissionNumber}"}""")
        ).andExpect(status().isConflict)

        // The second guardian still cannot see the child, and cannot unlink them either.
        check(children(secondGuardian).isEmpty())
        mockMvc.perform(delete("/parent/child/${child.id}").header("Authorization", auth(secondGuardian)))
            .andExpect(status().isNotFound)
    }

    @Test
    fun `only a parent account manages linked children`() {
        val school = school()
        val student = account(Role.STUDENT, "Pupil Three", school.id, admission = "ADM-P4-" + suffix())
        val studentToken = token(student)

        mockMvc.perform(get("/parent/children").header("Authorization", auth(studentToken)))
            .andExpect(status().isForbidden)
        mockMvc.perform(
            post("/parent/child/link").header("Authorization", auth(studentToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"admissionNumber":"whatever"}""")
        ).andExpect(status().isForbidden)

        // And an unknown admission number is a 404, not a link.
        val parentToken = token(account(Role.PARENT, "Guardian C", school.id))
        mockMvc.perform(
            post("/parent/child/link").header("Authorization", auth(parentToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"admissionNumber":"ADM-NOPE-${suffix()}"}""")
        ).andExpect(status().isNotFound)
        mockMvc.perform(
            post("/parent/child/link").header("Authorization", auth(parentToken))
                .contentType(MediaType.APPLICATION_JSON).content("""{"admissionNumber":"  "}""")
        ).andExpect(status().isBadRequest)
    }

    // ---------------------------------------------------------------- helpers

    private fun suffix() = UUID.randomUUID().toString().replace("-", "").take(6)

    private fun school(): SchoolEntity = schools.save(
        SchoolEntity().apply {
            name = "Parent Portal School " + suffix()
            county = "Nairobi"
        }
    )

    private fun account(
        role: Role,
        name: String,
        schoolId: UUID,
        admission: String? = null,
    ): UserEntity {
        // Captured outside the apply block: an unqualified `schoolId` inside it would
        // resolve to the entity's own (null) property.
        val school = schoolId
        return users.save(
            UserEntity().apply {
                val n = sequence.incrementAndGet()
                phoneNumber = "079" + (2_000_000 + n)
                email = phoneNumber + "@parent.test"
                passwordHash = passwordEncoder.encode("password123") ?: error("encode")
                this.name = name
                this.role = role
                this.schoolId = school
                gradeLevel = if (role == Role.STUDENT) "Grade 6" else null
                studentAdmissionNumber = admission
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

    private fun children(parentToken: String): List<LinkedChildPayload> = objectMapper.readValue(
        mockMvc.perform(get("/parent/children").header("Authorization", auth(parentToken)))
            .andExpect(status().isOk).andReturn().response.contentAsString,
        Array<LinkedChildPayload>::class.java,
    ).toList()

    private fun auth(token: String) = "Bearer " + token
}
