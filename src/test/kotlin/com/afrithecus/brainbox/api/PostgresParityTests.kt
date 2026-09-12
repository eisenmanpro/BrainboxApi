package com.afrithecus.brainbox.api

import com.afrithecus.brainbox.api.auth.web.AuthResponse
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.model.Role
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import javax.sql.DataSource

/**
 * Runs the real stack against actual PostgreSQL (Flyway V1-V5 + Hibernate
 * validate + repository/API round-trips). Opt-in so the default suite needs no
 * database:
 *
 *   PG_PARITY=true DB_URL=jdbc:postgresql://localhost:5433/brainbox
 *   DB_USER=brainbox DB_PASSWORD=brainbox ./gradlew test --tests "*PostgresParityTests*"
 *
 * DB_URL defaults match compose.yaml (localhost:5432/brainbox).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("pgtest")
@Transactional
@EnabledIfEnvironmentVariable(named = "PG_PARITY", matches = "true")
class PostgresParityTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val userRepository: UserRepository,
    @Autowired private val dataSource: DataSource,
) {

    @Test
    fun `flyway migrated all schema objects`() {
        dataSource.connection.use { conn ->
            conn.createStatement().use { st ->
                val tables = st.executeQuery(
                    "select table_name from information_schema.tables " +
                        "where table_schema='public' order by table_name"
                )
                val names = mutableListOf<String>()
                while (tables.next()) names += tables.getString(1)
                for (expected in listOf(
                    "flyway_schema_history", "users", "schools", "user_sessions",
                    "refresh_tokens", "subscriptions", "teacher_codes",
                    "teacher_profiles", "idempotency_records", "user_settings",
                    "career_goals", "matching_schools", "interview_questions", "interview_sessions",
                    "topic_mastery", "user_achievements", "badges", "rewards",
                    "live_classes", "live_registrations", "live_attendance", "live_polls",
                    "cbc_projects", "cbc_project_votes", "cbc_project_comments", "cbc_project_views",
                    "notifications", "news_items", "school_details", "school_reviews",
                    "school_join_requests", "school_reports", "news_comments", "news_votes",
                    "news_reports", "cbc_project_tracks", "cbc_project_reports", "study_sessions",
                    "class_groups", "class_group_members", "class_group_messages", "class_group_polls", "class_group_reads",
                    "traditional_exams", "traditional_exam_subjects", "traditional_marks",
                    "traditional_subject_configs", "traditional_grading_configs", "traditional_confirmations",
                    "traditional_edit_requests", "traditional_edit_permissions",
                    "attendance_records",
                )) {
                    require(expected in names) { "missing table " + expected + "; got " + names }
                }
            }
        }
    }

    @Test
    fun `jpa roundtrip persists and reads on postgres`() {
        val user = UserEntity().apply {
            phoneNumber = "0755000001"
            email = "pg-user@example.com"
            passwordHash = "not-a-hash"
            name = "Postgres User"
            role = Role.STUDENT
        }
        userRepository.save(user)
        val found = userRepository.findByPhoneNumber("0755000001")
        requireNotNull(found)
        check(found.id == user.id)
        check(found.email == "pg-user@example.com")
    }

    @Test
    fun `auth endpoints work end to end on postgres`() {
        mockMvc.perform(
            post("/auth/signup").header("X-Device-Id", "pg-dev")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"PG Student","phoneNumber":"0755000002","password":"password123","role":"STUDENT"}""")
        ).andExpect(status().isOk)

        val login = mockMvc.perform(
            post("/auth/login").header("X-Device-Id", "pg-dev")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"identifier":"0755000002","password":"password123"}""")
        ).andExpect(status().isOk).andReturn().response.contentAsString
        val auth = objectMapper.readValue(login, AuthResponse::class.java)
        check(auth.user.studentAdmissionNumber != null)

        mockMvc.perform(
            get("/auth/me").header("Authorization", "Bearer " + auth.sessionToken)
        ).andExpect(status().isOk)
    }
}
