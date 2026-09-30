package com.afrithecus.brainbox.api.auth

import com.afrithecus.brainbox.api.identity.repository.SchoolRepository
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional

/**
 * product_ops_roadmap item 5: signup accepts a school by name so a learner can join theirs,
 * but a *variant* of an existing name must not add a second public school. (The console is
 * meant to be the origin of school data.)
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class SchoolSignupNameTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val schools: SchoolRepository,
) {

    private fun signup(phone: String, schoolName: String) {
        val body = "{\"name\":\"Learner " + phone + "\",\"phoneNumber\":\"" + phone +
            "\",\"password\":\"password123\",\"role\":\"STUDENT\",\"schoolName\":\"" +
            schoolName + "\"}"
        mockMvc.perform(
            post("/auth/signup").header("X-Device-Id", "dev")
                .contentType(MediaType.APPLICATION_JSON).content(body)
        ).andExpect(status().isOk)
    }

    private fun alphaSchools() = schools.findAllByOrderByNameAsc()
        .filter { it.name.trim().replace(Regex("\\s+"), " ").equals("Alpha School", ignoreCase = true) }

    @Test
    fun `school name variants reuse one school`() {
        val before = schools.findAllByOrderByNameAsc().size

        signup("0778100001", "Alpha School")
        signup("0778100002", "  alpha   school  ")
        signup("0778100003", "ALPHA SCHOOL")

        check(alphaSchools().size == 1) {
            "expected exactly one Alpha School, got " + alphaSchools().map { it.name }
        }
        check(schools.findAllByOrderByNameAsc().size == before + 1) {
            "only one new school may be created, got " + (schools.findAllByOrderByNameAsc().size - before)
        }
    }

    @Test
    fun `a genuinely different name still creates its own school`() {
        val before = schools.findAllByOrderByNameAsc().size
        signup("0778100011", "Mwangaza Academy")
        signup("0778100012", "Mwangaza Secondary")
        check(schools.findAllByOrderByNameAsc().size == before + 2) {
            "distinct schools must still be created"
        }
    }
}
