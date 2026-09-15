package com.afrithecus.brainbox.api.content

import com.afrithecus.brainbox.api.auth.web.AuthResponse
import com.afrithecus.brainbox.api.content.entity.GenerationJobEntity
import com.afrithecus.brainbox.api.content.repository.GenerationJobRepository
import com.afrithecus.brainbox.api.content.web.ContentQueueBudget
import com.afrithecus.brainbox.api.content.web.ContentQueueBudgetRequest
import com.afrithecus.brainbox.api.content.web.ContentQueueSourcesRequest
import com.afrithecus.brainbox.api.content.web.ContentQueueSummary
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.util.UUID

/**
 * H2 admin runtime surface: /admin/content/queue summary, pause/resume and
 * sources. ADMIN only, matching the other admin surfaces.
 */
@SpringBootTest(properties = ["app.content.run-mode=api"])
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ContentQueueAdminTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val users: UserRepository,
    @Autowired private val passwordEncoder: PasswordEncoder,
    @Autowired private val generationJobs: GenerationJobRepository,
) {

    @Test
    fun `the admin queue summary reports depth by status and by source`() {
        val admin = seed("0744620501", Role.ADMIN, "Queue Admin")
        saveJob("QUEUED", GenerationJobSource.USER)
        saveJob("QUEUED", GenerationJobSource.BATCH)
        saveJob("RUNNING", GenerationJobSource.BATCH)

        val summary = summary(admin)

        check(!summary.paused)
        check(summary.sources == GenerationJobSource.ALL)
        check(summary.depth["QUEUED"] == 2L)
        check(summary.depth["RUNNING"] == 1L)
        check(summary.depth["SUCCEEDED"] == 0L)
        check(summary.depth["FAILED"] == 0L)
        check(summary.bySource["USER"]!!["QUEUED"] == 1L)
        check(summary.bySource["BATCH"]!!["QUEUED"] == 1L)
        check(summary.bySource["BATCH"]!!["RUNNING"] == 1L)
        check(summary.bySource["PROACTIVE"]!!["QUEUED"] == 0L)
    }

    @Test
    fun `pause and resume flip the runtime policy and are reflected in the summary`() {
        val admin = seed("0744620502", Role.ADMIN, "Queue Admin")

        val paused = read(
            mockMvc.perform(post("/admin/content/queue/pause").header("Authorization", auth(token(admin))))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            ContentQueueSummary::class.java,
        )
        check(paused.paused) { "pause must report paused=true" }

        check(summary(admin).paused) { "the pause must persist on the next summary read" }

        val resumed = read(
            mockMvc.perform(post("/admin/content/queue/resume").header("Authorization", auth(token(admin))))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            ContentQueueSummary::class.java,
        )
        check(!resumed.paused) { "resume must report paused=false" }
    }

    @Test
    fun `sources sets known sources and rejects an unknown one`() {
        val admin = seed("0744620503", Role.ADMIN, "Queue Admin")

        val body = objectMapper.writeValueAsString(ContentQueueSourcesRequest(sources = listOf("USER")))
        val summary = read(
            mockMvc.perform(
                put("/admin/content/queue/sources")
                    .header("Authorization", auth(token(admin)))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body)
            ).andExpect(status().isOk).andReturn().response.contentAsString,
            ContentQueueSummary::class.java,
        )
        check(summary.sources == listOf(GenerationJobSource.USER))

        // Lower case is normalised, not rejected.
        val lower = objectMapper.writeValueAsString(ContentQueueSourcesRequest(sources = listOf("user", "batch")))
        val normalized = read(
            mockMvc.perform(
                put("/admin/content/queue/sources")
                    .header("Authorization", auth(token(admin)))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(lower)
            ).andExpect(status().isOk).andReturn().response.contentAsString,
            ContentQueueSummary::class.java,
        )
        check(normalized.sources == listOf(GenerationJobSource.USER, GenerationJobSource.BATCH))

        val unknown = objectMapper.writeValueAsString(ContentQueueSourcesRequest(sources = listOf("SEED")))
        mockMvc.perform(
            put("/admin/content/queue/sources")
                .header("Authorization", auth(token(admin)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(unknown)
        ).andExpect(status().isBadRequest)
    }

    @Test
    fun `the queue surface requires an admin token`() {
        val sourcesBody = objectMapper.writeValueAsString(ContentQueueSourcesRequest(sources = listOf("USER")))

        mockMvc.perform(get("/admin/content/queue")).andExpect(status().isUnauthorized)
        mockMvc.perform(post("/admin/content/queue/pause")).andExpect(status().isUnauthorized)
        mockMvc.perform(post("/admin/content/queue/resume")).andExpect(status().isUnauthorized)
        mockMvc.perform(
            put("/admin/content/queue/sources").contentType(MediaType.APPLICATION_JSON).content(sourcesBody)
        ).andExpect(status().isUnauthorized)
        mockMvc.perform(get("/admin/content/queue/budget")).andExpect(status().isUnauthorized)
        mockMvc.perform(
            put("/admin/content/queue/budget").contentType(MediaType.APPLICATION_JSON).content("{\"platformDailyJobs\":5}")
        ).andExpect(status().isUnauthorized)

        val teacher = seed("0744620504", Role.TEACHER, "Queue Teacher")
        mockMvc.perform(get("/admin/content/queue").header("Authorization", auth(token(teacher))))
            .andExpect(status().isForbidden)
        mockMvc.perform(post("/admin/content/queue/pause").header("Authorization", auth(token(teacher))))
            .andExpect(status().isForbidden)
        mockMvc.perform(post("/admin/content/queue/resume").header("Authorization", auth(token(teacher))))
            .andExpect(status().isForbidden)
        mockMvc.perform(
            put("/admin/content/queue/sources")
                .header("Authorization", auth(token(teacher)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(sourcesBody)
        ).andExpect(status().isForbidden)
        mockMvc.perform(
            get("/admin/content/queue/budget").header("Authorization", auth(token(teacher)))
        ).andExpect(status().isForbidden)
        mockMvc.perform(
            put("/admin/content/queue/budget")
                .header("Authorization", auth(token(teacher)))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"platformDailyJobs\":5}")
        ).andExpect(status().isForbidden)
    }

    @Test
    fun `the budget surface reports and updates the daily budgets`() {
        val admin = seed("0744620505", Role.ADMIN, "Queue Admin")

        val initial = budget(admin)
        check(initial.platformBudget == 500) { "default platform budget must be 500" }
        check(initial.schoolBudget == 100) { "default school budget must be 100" }
        check(initial.platformUsed >= 0L)

        val body = objectMapper.writeValueAsString(
            ContentQueueBudgetRequest(platformDailyJobs = 7, schoolDailyJobs = 3)
        )
        val updated = read(
            mockMvc.perform(
                put("/admin/content/queue/budget")
                    .header("Authorization", auth(token(admin)))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body)
            ).andExpect(status().isOk).andReturn().response.contentAsString,
            ContentQueueBudget::class.java,
        )
        check(updated.platformBudget == 7)
        check(updated.schoolBudget == 3)
        check(budget(admin).platformBudget == 7) { "the write must persist on the next read" }

        // A negative value is rejected.
        val negative = objectMapper.writeValueAsString(ContentQueueBudgetRequest(platformDailyJobs = -1))
        mockMvc.perform(
            put("/admin/content/queue/budget")
                .header("Authorization", auth(token(admin)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(negative)
        ).andExpect(status().isBadRequest)

        // No value at all is rejected.
        mockMvc.perform(
            put("/admin/content/queue/budget")
                .header("Authorization", auth(token(admin)))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}")
        ).andExpect(status().isBadRequest)

        // An unknown/malformed value type is rejected.
        mockMvc.perform(
            put("/admin/content/queue/budget")
                .header("Authorization", auth(token(admin)))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"platformDailyJobs\":\"many\"}")
        ).andExpect(status().isBadRequest)
    }

    // ---------------------------------------------------------------- fixtures

    private fun summary(admin: UserEntity): ContentQueueSummary = read(
        mockMvc.perform(get("/admin/content/queue").header("Authorization", auth(token(admin))))
            .andExpect(status().isOk).andReturn().response.contentAsString,
        ContentQueueSummary::class.java,
    )

    private fun budget(admin: UserEntity): ContentQueueBudget = read(
        mockMvc.perform(get("/admin/content/queue/budget").header("Authorization", auth(token(admin))))
            .andExpect(status().isOk).andReturn().response.contentAsString,
        ContentQueueBudget::class.java,
    )

    private fun saveJob(status: String, source: String) {
        generationJobs.save(
            GenerationJobEntity().apply {
                generationKey = "ke:cbc:grade4:mat-num-frac:queue:" + UUID.randomUUID()
                taskType = "NOTES"
                gradeLevel = "Grade 4"
                this.status = status
                this.source = source
            }
        )
    }

    private fun seed(phone: String, role: Role, name: String): UserEntity =
        users.save(
            UserEntity().apply {
                phoneNumber = phone
                email = phone + "@queue.test"
                passwordHash = passwordEncoder.encode("password123") ?: error("encode")
                this.name = name
                this.role = role
                isActive = true
                isVerified = true
            }
        )

    private fun token(user: UserEntity): String {
        val body = mockMvc.perform(
            post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"identifier\":\"" + user.email + "\",\"password\":\"password123\"}")
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return objectMapper.readValue(body, AuthResponse::class.java).sessionToken!!
    }

    private fun auth(token: String) = "Bearer " + token

    private fun <T> read(body: String, type: Class<T>): T = objectMapper.readValue(body, type)
}
