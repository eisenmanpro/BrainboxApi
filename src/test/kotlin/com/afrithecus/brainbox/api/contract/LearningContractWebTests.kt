package com.afrithecus.brainbox.api.contract

import com.afrithecus.brainbox.api.auth.web.AuthResponse
import com.afrithecus.brainbox.api.contract.web.ContractCommitmentPayload
import com.afrithecus.brainbox.api.contract.web.ContractTemplatePayload
import com.afrithecus.brainbox.api.contract.web.LearningContractPayload
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.model.Role
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import com.afrithecus.brainbox.api.notification.web.AppNotificationPayload
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.util.UUID

/** Teacher learning contracts (doc 04 section 11). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class LearningContractWebTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val userRepository: UserRepository,
    @Autowired private val passwordEncoder: PasswordEncoder,
) {
    private fun user(role: Role, name: String, phone: String, parentId: UUID? = null): UserEntity =
        userRepository.save(UserEntity().apply {
            phoneNumber = phone
            email = phone + "@contract.test"
            passwordHash = passwordEncoder.encode("password123") ?: error("encode")
            this.name = name
            this.role = role
            this.parentUserId = parentId
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
    fun `contract crud commitments reminders and templates`() {
        val teacher = user(Role.TEACHER, "Class Teacher", "0755070001")
        val other = user(Role.TEACHER, "Other Teacher", "0755070002")
        val parent = user(Role.PARENT, "Parent One", "0755070003")
        val child = user(Role.STUDENT, "Alice Learner", "0755070004", parentId = parent.id)
        val t = token(teacher)

        val templates = objectMapper.readValue(
            mockMvc.perform(get("/teacher/learning-contracts/templates").header("Authorization", auth(t)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            Array<ContractTemplatePayload>::class.java,
        )
        check(templates.size >= 3)
        check(templates.all { it.defaultCommitments.isNotEmpty() })

        val contract = LearningContractPayload(
            id = "lc_1",
            childId = child.id.toString(),
            teacherId = "",
            term = "TERM_1",
            commitments = listOf(
                ContractCommitmentPayload(id = "cm_1", party = "STUDENT", text = "Attend all classes"),
                ContractCommitmentPayload(id = "cm_2", party = "PARENT", text = "Check homework weekly"),
            ),
        )
        val created = objectMapper.readValue(
            mockMvc.perform(post("/teacher/learning-contracts").header("Authorization", auth(t))
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(contract)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            LearningContractPayload::class.java,
        )
        check(created.id == "lc_1")
        check(created.teacherId == teacher.id.toString())
        check(created.status == "ACTIVE")
        check(created.commitments.size == 2)
        check(created.commitments.first { it.id == "cm_1" }.isCompleted.not())

        // Replay of the same client id upserts.
        mockMvc.perform(post("/teacher/learning-contracts").header("Authorization", auth(t))
            .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(contract)))
            .andExpect(status().isOk)
        val listed = objectMapper.readValue(
            mockMvc.perform(get("/teacher/learning-contracts").header("Authorization", auth(t)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            Array<LearningContractPayload>::class.java,
        )
        check(listed.size == 1)

        // Commitment completion.
        val patched = objectMapper.readValue(
            mockMvc.perform(patch("/teacher/learning-contracts/lc_1/commitment/cm_1")
                .param("isCompleted", "true").param("notes", "All present")
                .header("Authorization", auth(t)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            LearningContractPayload::class.java,
        )
        val commitment = patched.commitments.first { it.id == "cm_1" }
        check(commitment.isCompleted)
        check(commitment.completionDate != null)
        check(commitment.notes == "All present")

        // Update replaces the commitment set.
        val updated = objectMapper.readValue(
            mockMvc.perform(put("/teacher/learning-contracts/lc_1").header("Authorization", auth(t))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                    contract.copy(status = "COMPLETED", commitments = listOf(
                        ContractCommitmentPayload(id = "cm_1", party = "STUDENT", text = "Attend all classes", isCompleted = true),
                    )),
                )))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            LearningContractPayload::class.java,
        )
        check(updated.status == "COMPLETED")
        check(updated.commitments.size == 1)

        // Reminder fan-out to the linked parent.
        mockMvc.perform(post("/teacher/learning-contracts/lc_1/remind").param("party", "parent")
            .header("Authorization", auth(t)))
            .andExpect(status().isNoContent)
        val notifications = objectMapper.readValue(
            mockMvc.perform(get("/notifications").param("userId", parent.id.toString())
                .header("Authorization", auth(token(parent))))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            Array<AppNotificationPayload>::class.java,
        )
        check(notifications.any { it.title == "Learning contract reminder" })

        // Ownership and role gates.
        mockMvc.perform(get("/teacher/learning-contracts/lc_1").header("Authorization", auth(token(other))))
            .andExpect(status().isForbidden)
        mockMvc.perform(get("/teacher/learning-contracts").header("Authorization", auth(token(child))))
            .andExpect(status().isForbidden)

        mockMvc.perform(delete("/teacher/learning-contracts/lc_1").header("Authorization", auth(t)))
            .andExpect(status().isNoContent)
        mockMvc.perform(delete("/teacher/learning-contracts/lc_1").header("Authorization", auth(t)))
            .andExpect(status().isNoContent)
    }
}
