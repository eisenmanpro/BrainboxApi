package com.afrithecus.brainbox.api.cbc

import com.afrithecus.brainbox.api.auth.web.AuthResponse
import com.afrithecus.brainbox.api.cbc.web.AddProjectCommentRequest
import com.afrithecus.brainbox.api.cbc.web.CbcProjectPayload
import com.afrithecus.brainbox.api.cbc.web.CommentListResponsePayload
import com.afrithecus.brainbox.api.cbc.web.ProjectCommentPayload
import com.afrithecus.brainbox.api.cbc.web.ProjectListResponsePayload
import com.afrithecus.brainbox.api.cbc.web.PublicReportResponsePayload
import com.afrithecus.brainbox.api.cbc.web.PublicTrackResponsePayload
import com.afrithecus.brainbox.api.cbc.web.PublicVoteResponsePayload
import com.afrithecus.brainbox.api.cbc.web.SubmitCbcProjectRequest
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.util.UUID

/**
 * Public CBC project flow (docs/ongoing/api_cbc_public_changes.md): unauthenticated
 * browse, guest-attributed votes/comments/tracks/reports and unique guest views.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class CbcPublicWebTests(
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
            name = "Public " + phone
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
        val body = """{"name":"PublicStudent ${phone}","phoneNumber":"${phone}","password":"password123","role":"STUDENT"}"""
        val response = mockMvc.perform(
            post("/auth/signup").header("X-Device-Id", "dev")
                .contentType(MediaType.APPLICATION_JSON).content(body)
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return objectMapper.readValue(response, AuthResponse::class.java)
    }

    private fun submit(student: AuthResponse, title: String): CbcProjectPayload {
        val request = SubmitCbcProjectRequest(
            title = title,
            description = "Public showcase project.",
            subject = "Science",
            cbcStrand = "CS3",
            cbcSubStrand = "SS-CS3-1",
            gradeBand = "JUNIOR",
            mediaUrls = listOf("https://cdn.brainbox.com/a.jpg"),
        )
        val response = mockMvc.perform(
            post("/cbc/projects").header("Authorization", auth(student.sessionToken!!))
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(request))
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return objectMapper.readValue(response, CbcProjectPayload::class.java)
    }

    private fun setStatus(teacherToken: String, projectId: String, status: String) {
        mockMvc.perform(
            patch("/cbc/projects/${projectId}/status").header("Authorization", auth(teacherToken))
                .contentType(MediaType.APPLICATION_JSON).content("""{"status":"${status}"}""")
        ).andExpect(status().isOk)
    }

    private fun guestId(): String = "guest_" + UUID.randomUUID()

    @Test
    fun `public browse exposes only approved projects`() {
        newUser("0779200000", "cbc.public.teacher@test", Role.TEACHER)
        val teacher = login("cbc.public.teacher@test")
        val student = signup("0779200001")

        val pending = submit(student, "Pending Project")
        val publicList = objectMapper.readValue(
            mockMvc.perform(get("/cbc/public/projects")).andExpect(status().isOk).andReturn().response.contentAsString,
            ProjectListResponsePayload::class.java,
        )
        check(publicList.projects.none { it.id == pending.id })

        setStatus(teacher, pending.id, "APPROVED")
        val after = objectMapper.readValue(
            mockMvc.perform(get("/cbc/public/projects")).andExpect(status().isOk).andReturn().response.contentAsString,
            ProjectListResponsePayload::class.java,
        )
        check(after.projects.any { it.id == pending.id })

        val detailBody = mockMvc.perform(get("/cbc/public/projects/${pending.id}"))
            .andExpect(status().isOk).andReturn().response.contentAsString
        check(objectMapper.readValue(detailBody, CbcProjectPayload::class.java).title == "Pending Project")

        setStatus(teacher, pending.id, "REMOVED")
        mockMvc.perform(get("/cbc/public/projects/${pending.id}")).andExpect(status().isNotFound)
    }

    @Test
    fun `guest votes are single count and adjust on change`() {
        newUser("0779200010", "cbc.public.teacher2@test", Role.TEACHER)
        val teacher = login("cbc.public.teacher2@test")
        val student = signup("0779200011")
        val project = submit(student, "Votable Project")
        setStatus(teacher, project.id, "APPROVED")
        val guest = guestId()

        val up = objectMapper.readValue(
            mockMvc.perform(
                post("/cbc/public/projects/${project.id}/vote").header("X-Guest-Id", guest)
                    .contentType(MediaType.APPLICATION_JSON).content("""{"voteType":"UPVOTE"}""")
            ).andExpect(status().isOk).andReturn().response.contentAsString,
            PublicVoteResponsePayload::class.java,
        )
        check(up.upvotes == 1)
        check(up.userVote == "UPVOTE")

        mockMvc.perform(
            post("/cbc/public/projects/${project.id}/vote").header("X-Guest-Id", guest)
                .contentType(MediaType.APPLICATION_JSON).content("""{"voteType":"UPVOTE"}""")
        ).andExpect(status().isOk)
        val detail = objectMapper.readValue(
            mockMvc.perform(get("/cbc/public/projects/${project.id}").header("X-Guest-Id", guest))
                .andReturn().response.contentAsString,
            CbcProjectPayload::class.java,
        )
        check(detail.upvotes == 1)
        check(detail.isLiked)

        val down = objectMapper.readValue(
            mockMvc.perform(
                post("/cbc/public/projects/${project.id}/vote").header("X-Guest-Id", guest)
                    .contentType(MediaType.APPLICATION_JSON).content("""{"voteType":"DOWNVOTE"}""")
            ).andReturn().response.contentAsString,
            PublicVoteResponsePayload::class.java,
        )
        check(down.upvotes == 0)
        check(down.downvotes == 1)

        val cleared = objectMapper.readValue(
            mockMvc.perform(delete("/cbc/public/projects/${project.id}/vote").header("X-Guest-Id", guest))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            PublicVoteResponsePayload::class.java,
        )
        check(cleared.downvotes == 0)
        check(cleared.userVote == null)

        mockMvc.perform(
            post("/cbc/public/projects/${project.id}/vote").header("X-Guest-Id", "not-a-guest")
                .contentType(MediaType.APPLICATION_JSON).content("""{"voteType":"UPVOTE"}""")
        ).andExpect(status().isBadRequest)
    }

    @Test
    fun `guest comments tracks and reports`() {
        newUser("0779200020", "cbc.public.teacher3@test", Role.TEACHER)
        val teacher = login("cbc.public.teacher3@test")
        val student = signup("0779200021")
        val project = submit(student, "Engageable Project")
        setStatus(teacher, project.id, "APPROVED")
        val guest = guestId()

        val comment = objectMapper.readValue(
            mockMvc.perform(
                post("/cbc/public/projects/${project.id}/comments").header("X-Guest-Id", guest)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"content":"Great project!","mentions":[]}""")
            ).andExpect(status().isOk).andReturn().response.contentAsString,
            ProjectCommentPayload::class.java,
        )
        check(comment.userId.startsWith("guest_"))
        check(comment.userName.startsWith("Guest "))
        check(comment.userRole == null)

        val comments = objectMapper.readValue(
            mockMvc.perform(get("/cbc/public/projects/${project.id}/comments"))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            CommentListResponsePayload::class.java,
        )
        check(comments.comments.single().content == "Great project!")

        val tracked = objectMapper.readValue(
            mockMvc.perform(
                post("/cbc/public/projects/${project.id}/track").header("X-Guest-Id", guest)
                    .contentType(MediaType.APPLICATION_JSON).content("""{"email":"parent@example.com"}""")
            ).andExpect(status().isOk).andReturn().response.contentAsString,
            PublicTrackResponsePayload::class.java,
        )
        check(tracked.tracked)
        val again = objectMapper.readValue(
            mockMvc.perform(
                post("/cbc/public/projects/${project.id}/track").header("X-Guest-Id", guest)
                    .contentType(MediaType.APPLICATION_JSON).content("""{"email":"parent@example.com"}""")
            ).andReturn().response.contentAsString,
            PublicTrackResponsePayload::class.java,
        )
        check(again.message!!.contains("already"))
        mockMvc.perform(
            post("/cbc/public/projects/${project.id}/track").header("X-Guest-Id", guest)
                .contentType(MediaType.APPLICATION_JSON).content("""{"email":"not-an-email"}""")
        ).andExpect(status().isBadRequest)

        val report = objectMapper.readValue(
            mockMvc.perform(
                post("/cbc/public/projects/${project.id}/report").header("X-Guest-Id", guest)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"reason":"Spam or misleading","details":"Looks fake."}""")
            ).andExpect(status().isOk).andReturn().response.contentAsString,
            PublicReportResponsePayload::class.java,
        )
        check(report.reported)
        mockMvc.perform(
            post("/cbc/public/projects/${project.id}/report").header("X-Guest-Id", guest)
                .contentType(MediaType.APPLICATION_JSON).content("""{"reason":"Aliens"}""")
        ).andExpect(status().isBadRequest)
    }

    @Test
    fun `public views count once per guest`() {
        newUser("0779200030", "cbc.public.teacher4@test", Role.TEACHER)
        val teacher = login("cbc.public.teacher4@test")
        val student = signup("0779200031")
        val project = submit(student, "Viewed Project")
        setStatus(teacher, project.id, "APPROVED")
        val guest = guestId()
        val other = guestId()

        mockMvc.perform(
            post("/cbc/public/projects/${project.id}/view").header("X-Guest-Id", guest)
        ).andExpect(status().isNoContent)
        mockMvc.perform(
            post("/cbc/public/projects/${project.id}/view").header("X-Guest-Id", guest)
        ).andExpect(status().isNoContent)
        mockMvc.perform(
            post("/cbc/public/projects/${project.id}/view").header("X-Guest-Id", other)
        ).andExpect(status().isNoContent)

        val detail = objectMapper.readValue(
            mockMvc.perform(get("/cbc/public/projects/${project.id}")).andReturn().response.contentAsString,
            CbcProjectPayload::class.java,
        )
        check(detail.viewCount == 2)
    }
}
