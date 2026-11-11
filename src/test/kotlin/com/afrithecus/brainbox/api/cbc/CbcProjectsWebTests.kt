package com.afrithecus.brainbox.api.cbc

import com.afrithecus.brainbox.api.auth.web.AuthResponse
import com.afrithecus.brainbox.api.cbc.web.AddProjectCommentRequest
import com.afrithecus.brainbox.api.cbc.web.CbcProjectPayload
import com.afrithecus.brainbox.api.cbc.web.CbcVoteRequest
import com.afrithecus.brainbox.api.cbc.web.CommentListResponsePayload
import com.afrithecus.brainbox.api.cbc.web.ProjectListResponsePayload
import com.afrithecus.brainbox.api.cbc.web.ProjectVotePayload
import com.afrithecus.brainbox.api.cbc.web.SubmitCbcProjectRequest
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.util.UUID

/**
 * CBC project showcase (doc 06 §3): submission/moderation lifecycle, feed
 * filtering/sorting, voting with no double count, threaded comments and unique
 * view tracking.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class CbcProjectsWebTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val userRepository: UserRepository,
    @Autowired private val schoolRepository: SchoolRepository,
    @Autowired private val passwordEncoder: PasswordEncoder,
) {

    private fun auth(token: String) = "Bearer " + token

    private fun newUser(phone: String, email: String, role: Role): UserEntity =
        userRepository.save(UserEntity().apply {
            this.phoneNumber = phone
            this.email = email
            passwordHash = passwordEncoder.encode("password123") ?: error("encode")
            name = "Cbc " + phone
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
        val body = """{"name":"CbcStudent ${phone}","phoneNumber":"${phone}","password":"password123","role":"STUDENT"}"""
        val response = mockMvc.perform(
            post("/auth/signup").header("X-Device-Id", "dev")
                .contentType(MediaType.APPLICATION_JSON).content(body)
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return objectMapper.readValue(response, AuthResponse::class.java)
    }

    private fun submit(student: AuthResponse, title: String, subject: String = "Science", mediaUrls: List<String> = listOf("https://cdn.brainbox.com/a.jpg")): CbcProjectPayload {
        val request = SubmitCbcProjectRequest(
            title = title,
            description = "A detailed CBC project description.",
            subject = subject,
            cbcStrand = "CS3",
            cbcSubStrand = "SS-CS3-1",
            gradeBand = "JUNIOR",
            mediaUrls = mediaUrls,
            rubricScores = mapOf("Creativity" to 4),
            tags = listOf("innovation"),
        )
        val response = mockMvc.perform(
            post("/cbc/projects").header("Authorization", auth(student.sessionToken!!))
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(request))
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return objectMapper.readValue(response, CbcProjectPayload::class.java)
    }

    private fun approve(staffToken: String, projectId: String, status: String = "APPROVED"): CbcProjectPayload {
        val response = mockMvc.perform(
            patch("/cbc/projects/${projectId}/status").header("Authorization", auth(staffToken))
                .contentType(MediaType.APPLICATION_JSON).content("""{"status":"${status}"}""")
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return objectMapper.readValue(response, CbcProjectPayload::class.java)
    }

    private fun school(name: String): SchoolEntity = schoolRepository.save(SchoolEntity().apply { this.name = name })

    private fun userInSchool(phone: String, email: String, role: Role, schoolId: UUID): UserEntity =
        userRepository.save(UserEntity().apply {
            this.phoneNumber = phone
            this.email = email
            this.role = role
            this.schoolId = schoolId
            passwordHash = passwordEncoder.encode("password123") ?: error("encode")
            name = "Cbc " + phone
            isActive = true
            isVerified = true
        })

    @Test
    fun `staff upload credits school learners, edits while pending, and withdraws`() {
        val school = school("Project School")
        val otherSchool = school("Other School")
        userInSchool("0778910001", "project.teacher@test", Role.TEACHER, school.id)
        val learner = userInSchool("0778910002", "project.learner@test", Role.STUDENT, school.id)
        userInSchool("0778910004", "project.colleague@test", Role.TEACHER, school.id)
        userInSchool("0778910003", "other.teacher@test", Role.TEACHER, otherSchool.id)
        val teacher = login("project.teacher@test")
        val colleague = login("project.colleague@test")
        val otherTeacher = login("other.teacher@test")

        val request = mapOf(
            "title" to "Water filter",
            "description" to "A learner-built filter project.",
            "subject" to "Science",
            "cbcStrand" to "CS3",
            "cbcSubStrand" to "SS-CS3-1",
            "gradeBand" to "JUNIOR",
            "rubricScores" to mapOf("Research" to 4),
            "studentIds" to listOf(learner.id.toString()),
            "showLearnerNames" to listOf(learner.id.toString()),
        )
        val createdBody = mockMvc.perform(
            post("/cbc/projects").header("Authorization", auth(teacher))
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(request))
        ).andExpect(status().isOk).andReturn().response.contentAsString
        val created = objectMapper.readValue(createdBody, CbcProjectPayload::class.java)
        check(created.studentIds == listOf(learner.id.toString()))
        check(created.showLearnerNames == listOf(learner.id.toString()))
        check(created.studentName == learner.name)

        // Staff "mine" is the school's submission lane, and rows the caller authored say so.
        val mine = mockMvc.perform(get("/cbc/projects/mine").header("Authorization", auth(teacher)))
            .andExpect(status().isOk).andReturn().response.contentAsString
        val mineRow = objectMapper.readValue(mine, ProjectListResponsePayload::class.java)
            .projects.first { it.id == created.id }
        check(mineRow.canEdit) { "the publishing teacher must be able to edit a pending upload" }

        // A colleague sees the same school lane but may not edit the teacher's upload.
        val colleagueMine = mockMvc.perform(get("/cbc/projects/mine").header("Authorization", auth(colleague)))
            .andExpect(status().isOk).andReturn().response.contentAsString
        val colleagueRow = objectMapper.readValue(colleagueMine, ProjectListResponsePayload::class.java)
            .projects.first { it.id == created.id }
        check(!colleagueRow.canEdit) { "a non-author must not be offered edit/withdraw" }

        // Edit while PENDING.
        val editedBody = mockMvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/cbc/projects/" + created.id)
                .header("Authorization", auth(teacher))
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(request + ("title" to "Water filter v2")))
        ).andExpect(status().isOk).andReturn().response.contentAsString
        check(objectMapper.readValue(editedBody, CbcProjectPayload::class.java).title == "Water filter v2")

        // Another school cannot moderate it.
        mockMvc.perform(
            patch("/cbc/projects/" + created.id + "/status").header("Authorization", auth(otherTeacher))
                .contentType(MediaType.APPLICATION_JSON).content("""{"status":"REMOVED","note":"not yours"}""")
        ).andExpect(status().isForbidden)

        // The school's own staff can take it down with a reviewer note.
        val removedBody = mockMvc.perform(
            patch("/cbc/projects/" + created.id + "/status").header("Authorization", auth(teacher))
                .contentType(MediaType.APPLICATION_JSON).content("""{"status":"REMOVED","note":"Please blur the faces"}""")
        ).andExpect(status().isOk).andReturn().response.contentAsString
        val removed = objectMapper.readValue(removedBody, CbcProjectPayload::class.java)
        check(removed.moderationNote == "Please blur the faces")

        // Author withdraws only while PENDING; after removal it is already terminal.
        mockMvc.perform(delete("/cbc/projects/" + created.id).header("Authorization", auth(teacher)))
            .andExpect(status().isConflict)
    }

    @Test
    fun `public payload never exposes credited learner identity`() {
        val school = school("Privacy School")
        val teacher = userInSchool("0778910010", "privacy.teacher@test", Role.TEACHER, school.id)
        val learner = userInSchool("0778910011", "privacy.learner@test", Role.STUDENT, school.id)
        val token = login("privacy.teacher@test")
        val request = mapOf(
            "title" to "Private credit",
            "description" to "A project whose learner is not named publicly.",
            "subject" to "Science",
            "cbcStrand" to "CS3",
            "cbcSubStrand" to "SS-CS3-1",
            "gradeBand" to "JUNIOR",
            "studentIds" to listOf(learner.id.toString()),
            "showLearnerNames" to emptyList<String>(),
        )
        val body = mockMvc.perform(
            post("/cbc/projects").header("Authorization", auth(token))
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(request))
        ).andExpect(status().isOk).andReturn().response.contentAsString
        val created = objectMapper.readValue(body, CbcProjectPayload::class.java)
        check(created.authorLabel == "A learner at Privacy School")

        mockMvc.perform(
            patch("/cbc/projects/" + created.id + "/status").header("Authorization", auth(token))
                .contentType(MediaType.APPLICATION_JSON).content("""{"status":"APPROVED"}""")
        ).andExpect(status().isOk)

        val publicBody = mockMvc.perform(get("/cbc/public/projects/" + created.id))
            .andExpect(status().isOk).andReturn().response.contentAsString
        val public = objectMapper.readValue(publicBody, CbcProjectPayload::class.java)
        check(public.studentId.isEmpty())
        check(public.studentName == "A learner at Privacy School")
        check(public.studentIds.isEmpty())
        check(public.showLearnerNames.isEmpty())
        check(public.authorLabel == "A learner at Privacy School")
        check(!publicBody.contains(learner.id.toString()))
    }

    @Test
    fun `submit moderate and feed filters`() {
        newUser("0778900000", "cbc.teacher@test", Role.TEACHER)
        val teacher = login("cbc.teacher@test")
        val student = signup("0778900001")

        val pending = submit(student, "Automated Irrigation", mediaUrls = listOf("https://cdn.brainbox.com/a.jpg", "https://www.youtube.com/watch?v=x"))
        check(pending.status == "PENDING")
        check(pending.studentName == student.user.name)
        check(pending.mediaTypes.containsAll(listOf("IMAGE", "VIDEO")))
        check(pending.coverImageUrl == "https://cdn.brainbox.com/a.jpg")

        // students can't moderate
        mockMvc.perform(
            patch("/cbc/projects/${pending.id}/status").header("Authorization", auth(student.sessionToken!!))
                .contentType(MediaType.APPLICATION_JSON).content("""{"status":"APPROVED"}""")
        ).andExpect(status().isForbidden)

        // pending submissions are absent from the default (APPROVED) feed
        val approvedFeed = objectMapper.readValue(
            mockMvc.perform(get("/cbc/projects").header("Authorization", auth(student.sessionToken!!)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            ProjectListResponsePayload::class.java,
        )
        check(approvedFeed.projects.none { it.id == pending.id })

        val approved = approve(teacher, pending.id)
        check(approved.status == "APPROVED")

        val feed = objectMapper.readValue(
            mockMvc.perform(get("/cbc/projects?subject=Science&search=Irrigation").header("Authorization", auth(student.sessionToken!!)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            ProjectListResponsePayload::class.java,
        )
        check(feed.total == 1)
        check(feed.projects.single().id == pending.id)
        check(feed.hasMore.not())

        val mine = objectMapper.readValue(
            mockMvc.perform(get("/cbc/projects/mine").header("Authorization", auth(student.sessionToken!!)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            ProjectListResponsePayload::class.java,
        )
        check(mine.projects.any { it.id == pending.id })

        approve(teacher, pending.id, "FEATURED")
        val featured = objectMapper.readValue(
            mockMvc.perform(get("/cbc/projects/featured").header("Authorization", auth(student.sessionToken!!)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            Array<CbcProjectPayload>::class.java,
        )
        check(featured.any { it.id == pending.id })
    }

    @Test
    fun `voting counts once and can be removed`() {
        newUser("0778900010", "cbc.teacher2@test", Role.TEACHER)
        val teacher = login("cbc.teacher2@test")
        val author = signup("0778900011")
        val voter = signup("0778900012")
        val project = approve(teacher, submit(author, "Eco Fashion").id)

        val up = mockMvc.perform(
            post("/cbc/projects/${project.id}/vote").header("Authorization", auth(voter.sessionToken!!))
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(CbcVoteRequest("UPVOTE")))
        ).andExpect(status().isOk).andReturn().response.contentAsString
        check(objectMapper.readValue(up, ProjectVotePayload::class.java).voteType == "UPVOTE")

        // repeat upvote is idempotent
        mockMvc.perform(
            post("/cbc/projects/${project.id}/vote").header("Authorization", auth(voter.sessionToken!!))
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(CbcVoteRequest("UPVOTE")))
        ).andExpect(status().isOk)
        var detail = objectMapper.readValue(
            mockMvc.perform(get("/cbc/projects/${project.id}").header("Authorization", auth(voter.sessionToken!!)))
                .andReturn().response.contentAsString,
            CbcProjectPayload::class.java,
        )
        check(detail.upvotes == 1)
        check(detail.isLiked)

        // switching adjusts both counters
        mockMvc.perform(
            post("/cbc/projects/${project.id}/vote").header("Authorization", auth(voter.sessionToken!!))
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(CbcVoteRequest("DOWNVOTE")))
        ).andExpect(status().isOk)
        detail = objectMapper.readValue(
            mockMvc.perform(get("/cbc/projects/${project.id}").header("Authorization", auth(voter.sessionToken!!)))
                .andReturn().response.contentAsString,
            CbcProjectPayload::class.java,
        )
        check(detail.upvotes == 0)
        check(detail.downvotes == 1)
        check(detail.isDisliked)

        mockMvc.perform(delete("/cbc/projects/${project.id}/vote").header("Authorization", auth(voter.sessionToken!!)))
            .andExpect(status().isNoContent)
        detail = objectMapper.readValue(
            mockMvc.perform(get("/cbc/projects/${project.id}").header("Authorization", auth(voter.sessionToken!!)))
                .andReturn().response.contentAsString,
            CbcProjectPayload::class.java,
        )
        check(detail.downvotes == 0)
        check(!detail.isDisliked)
    }

    @Test
    fun `threaded comments and unique views`() {
        newUser("0778900020", "cbc.teacher3@test", Role.TEACHER)
        val teacher = login("cbc.teacher3@test")
        val author = signup("0778900021")
        val commenter = signup("0778900022")
        val project = approve(teacher, submit(author, "History Documentary").id)

        val top = mockMvc.perform(
            post("/cbc/projects/${project.id}/comments").header("Authorization", auth(commenter.sessionToken!!))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(AddProjectCommentRequest("Great work!", mentions = listOf(author.user.name))))
        ).andExpect(status().isOk).andReturn().response.contentAsString
        val topComment = objectMapper.readValue(top, com.afrithecus.brainbox.api.cbc.web.ProjectCommentPayload::class.java)
        check(topComment.userName == commenter.user.name)
        check(topComment.mentions.contains(author.user.name))

        mockMvc.perform(
            post("/cbc/projects/${project.id}/comments").header("Authorization", auth(author.sessionToken!!))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(AddProjectCommentRequest("Thank you!", parentCommentId = topComment.id)))
        ).andExpect(status().isOk)

        val comments = objectMapper.readValue(
            mockMvc.perform(get("/cbc/projects/${project.id}/comments").header("Authorization", auth(commenter.sessionToken!!)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            CommentListResponsePayload::class.java,
        )
        check(comments.total == 1)
        check(comments.comments.single().replies.size == 1)
        check(comments.comments.single().replies.single().content == "Thank you!")

        val afterComment = objectMapper.readValue(
            mockMvc.perform(get("/cbc/projects/${project.id}").header("Authorization", auth(commenter.sessionToken!!)))
                .andReturn().response.contentAsString,
            CbcProjectPayload::class.java,
        )
        check(afterComment.commentCount == 2)

        // views count once per user
        mockMvc.perform(post("/cbc/projects/${project.id}/view").header("Authorization", auth(commenter.sessionToken!!)))
            .andExpect(status().isNoContent)
        mockMvc.perform(post("/cbc/projects/${project.id}/view").header("Authorization", auth(commenter.sessionToken!!)))
            .andExpect(status().isNoContent)
        mockMvc.perform(post("/cbc/projects/${project.id}/view").header("Authorization", auth(author.sessionToken!!)))
            .andExpect(status().isNoContent)
        val viewed = objectMapper.readValue(
            mockMvc.perform(get("/cbc/projects/${project.id}").header("Authorization", auth(author.sessionToken!!)))
                .andReturn().response.contentAsString,
            CbcProjectPayload::class.java,
        )
        check(viewed.viewCount == 2)

        // unknown parent comment is rejected
        mockMvc.perform(
            post("/cbc/projects/${project.id}/comments").header("Authorization", auth(commenter.sessionToken!!))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(AddProjectCommentRequest("orphan", parentCommentId = UUID.randomUUID().toString())))
        ).andExpect(status().isNotFound)
    }

    @Test
    fun `authentication and validation`() {
        newUser("0778900030", "cbc.teacher4@test", Role.TEACHER)
        val teacher = login("cbc.teacher4@test")
        val student = signup("0778900031")
        val project = approve(teacher, submit(student, "Validation Project").id)

        mockMvc.perform(get("/cbc/projects")).andExpect(status().isUnauthorized)

        mockMvc.perform(
            post("/cbc/projects").header("Authorization", auth(student.sessionToken!!))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"title":"","description":"x","subject":"Science","gradeBand":"JUNIOR"}""")
        ).andExpect(status().isBadRequest)

        mockMvc.perform(
            post("/cbc/projects/${project.id}/vote").header("Authorization", auth(student.sessionToken!!))
                .contentType(MediaType.APPLICATION_JSON).content("""{"voteType":"MAYBE"}""")
        ).andExpect(status().isBadRequest)

        mockMvc.perform(get("/cbc/projects/${UUID.randomUUID()}").header("Authorization", auth(student.sessionToken!!)))
            .andExpect(status().isNotFound)
        mockMvc.perform(get("/cbc/projects/not-a-uuid").header("Authorization", auth(student.sessionToken!!)))
            .andExpect(status().isBadRequest)
    }
}
