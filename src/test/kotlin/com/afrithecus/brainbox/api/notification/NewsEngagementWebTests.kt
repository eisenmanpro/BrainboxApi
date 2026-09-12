package com.afrithecus.brainbox.api.notification

import com.afrithecus.brainbox.api.auth.web.AuthResponse
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.model.Role
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import com.afrithecus.brainbox.api.notification.web.CreateNewsRequest
import com.afrithecus.brainbox.api.notification.web.NewsCommentPayload
import com.afrithecus.brainbox.api.notification.web.NewsItemPayload
import com.afrithecus.brainbox.api.notification.web.NewsReportResponsePayload
import com.afrithecus.brainbox.api.notification.web.NewsVoteResponsePayload
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

/**
 * News engagement (docs/ongoing/api_news_changes.md): public reads, authenticated
 * comments/votes/reports with server-owned tallies, and admin authoring on /news.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class NewsEngagementWebTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val userRepository: UserRepository,
    @Autowired private val passwordEncoder: PasswordEncoder,
) {

    private fun auth(token: String) = "Bearer " + token

    private fun newUser(phone: String, email: String, role: Role): UserEntity =
        userRepository.save(UserEntity().apply {
            this.phoneNumber = phone
            this.email = email
            passwordHash = passwordEncoder.encode("password123") ?: error("encode")
            name = "News " + phone
            this.role = role
            isActive = true
            isVerified = true
        })

    private fun login(email: String): String {
        val body = mockMvc.perform(
            post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("""{"identifier":"${email}","password":"password123"}""")
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return objectMapper.readValue(body, AuthResponse::class.java).sessionToken!!
    }

    private fun signup(phone: String): AuthResponse {
        val body = """{"name":"NewsStudent ${phone}","phoneNumber":"${phone}","password":"password123","role":"STUDENT"}"""
        val response = mockMvc.perform(
            post("/auth/signup").header("X-Device-Id", "dev")
                .contentType(MediaType.APPLICATION_JSON).content(body)
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return objectMapper.readValue(response, AuthResponse::class.java)
    }

    private fun publish(adminToken: String, title: String): NewsItemPayload {
        val request = CreateNewsRequest(title = title, content = "<p>Full article body</p>", category = "Official")
        val response = mockMvc.perform(
            post("/news").header("Authorization", auth(adminToken))
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(request))
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return objectMapper.readValue(response, NewsItemPayload::class.java)
    }

    @Test
    fun `public reads and admin authoring`() {
        newUser("0779300000", "news.admin@test", Role.ADMIN)
        val adminToken = login("news.admin@test")
        val article = publish(adminToken, "Calendar update")

        check(article.author == "BrainBox Editorial")
        check(article.status == "PUBLISHED")

        // reads are public
        val feed = objectMapper.readValue(
            mockMvc.perform(get("/news")).andExpect(status().isOk).andReturn().response.contentAsString,
            Array<NewsItemPayload>::class.java,
        )
        check(feed.any { it.id == article.id })
        val detail = objectMapper.readValue(
            mockMvc.perform(get("/news/${article.id}")).andExpect(status().isOk).andReturn().response.contentAsString,
            NewsItemPayload::class.java,
        )
        check(detail.content.contains("Full article body"))

        // update + delete are admin-only
        mockMvc.perform(
            put("/news/${article.id}").header("Authorization", auth(adminToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(CreateNewsRequest("Calendar update v2", "Updated body")))
        ).andExpect(status().isOk)
        mockMvc.perform(delete("/news/${article.id}").header("Authorization", auth(adminToken)))
            .andExpect(status().isNoContent)
        mockMvc.perform(get("/news/${article.id}")).andExpect(status().isNotFound)
    }

    @Test
    fun `comments votes and reports need a session`() {
        newUser("0779300010", "news.admin2@test", Role.ADMIN)
        val adminToken = login("news.admin2@test")
        val student = signup("0779300011")
        val article = publish(adminToken, "Engagement article")

        mockMvc.perform(
            post("/news/${article.id}/comments")
                .contentType(MediaType.APPLICATION_JSON).content("""{"content":"hello","authorName":"Anon"}""")
        ).andExpect(status().isUnauthorized)
        mockMvc.perform(
            post("/news/${article.id}/vote")
                .contentType(MediaType.APPLICATION_JSON).content("""{"vote":"UPVOTE"}""")
        ).andExpect(status().isUnauthorized)

        val comment = objectMapper.readValue(
            mockMvc.perform(
                post("/news/${article.id}/comments").header("Authorization", auth(student.sessionToken!!))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"content":"Congratulations to all winners!","authorName":"Jane Doe"}""")
            ).andExpect(status().isCreated).andReturn().response.contentAsString,
            NewsCommentPayload::class.java,
        )
        check(comment.userName == "Jane Doe")
        val comments = objectMapper.readValue(
            mockMvc.perform(get("/news/${article.id}/comments")).andExpect(status().isOk).andReturn().response.contentAsString,
            Array<NewsCommentPayload>::class.java,
        )
        check(comments.single().content.contains("Congratulations"))
        check(objectMapper.readValue(
            mockMvc.perform(get("/news/${article.id}")).andReturn().response.contentAsString,
            NewsItemPayload::class.java,
        ).commentCount == 1)

        val up = objectMapper.readValue(
            mockMvc.perform(
                post("/news/${article.id}/vote").header("Authorization", auth(student.sessionToken!!))
                    .contentType(MediaType.APPLICATION_JSON).content("""{"vote":"UPVOTE"}""")
            ).andExpect(status().isOk).andReturn().response.contentAsString,
            NewsVoteResponsePayload::class.java,
        )
        check(up.likes == 1)
        check(up.userVote == "UPVOTE")
        mockMvc.perform(
            post("/news/${article.id}/vote").header("Authorization", auth(student.sessionToken!!))
                .contentType(MediaType.APPLICATION_JSON).content("""{"vote":"UPVOTE"}""")
        ).andExpect(status().isOk)
        check(objectMapper.readValue(
            mockMvc.perform(get("/news/${article.id}").header("Authorization", auth(student.sessionToken!!)))
                .andReturn().response.contentAsString,
            NewsItemPayload::class.java,
        ).likes == 1)

        val down = objectMapper.readValue(
            mockMvc.perform(
                post("/news/${article.id}/vote").header("Authorization", auth(student.sessionToken!!))
                    .contentType(MediaType.APPLICATION_JSON).content("""{"vote":"DOWNVOTE"}""")
            ).andReturn().response.contentAsString,
            NewsVoteResponsePayload::class.java,
        )
        check(down.likes == 0)
        check(down.dislikes == 1)
        val none = objectMapper.readValue(
            mockMvc.perform(
                post("/news/${article.id}/vote").header("Authorization", auth(student.sessionToken!!))
                    .contentType(MediaType.APPLICATION_JSON).content("""{"vote":"NONE"}""")
            ).andReturn().response.contentAsString,
            NewsVoteResponsePayload::class.java,
        )
        check(none.dislikes == 0)
        check(none.userVote == "NONE")
        mockMvc.perform(
            post("/news/${article.id}/vote").header("Authorization", auth(student.sessionToken!!))
                .contentType(MediaType.APPLICATION_JSON).content("""{"vote":"MAYBE"}""")
        ).andExpect(status().isBadRequest)

        val report = objectMapper.readValue(
            mockMvc.perform(
                post("/news/${article.id}/report").header("Authorization", auth(student.sessionToken!!))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"reason":"Spam or misleading","details":"Headline mismatch."}""")
            ).andExpect(status().isAccepted).andReturn().response.contentAsString,
            NewsReportResponsePayload::class.java,
        )
        check(report.status == "RECEIVED")
        check(report.reportId.isNotBlank())
        mockMvc.perform(
            post("/news/${article.id}/report").header("Authorization", auth(student.sessionToken!!))
                .contentType(MediaType.APPLICATION_JSON).content("""{"reason":"Aliens"}""")
        ).andExpect(status().isBadRequest)

        // students cannot publish
        mockMvc.perform(
            post("/news").header("Authorization", auth(student.sessionToken!!))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(CreateNewsRequest("Nope", "not allowed")))
        ).andExpect(status().isForbidden)
    }
}
