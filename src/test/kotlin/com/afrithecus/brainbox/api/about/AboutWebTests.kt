package com.afrithecus.brainbox.api.about

import com.afrithecus.brainbox.api.about.web.FaqPayload
import com.afrithecus.brainbox.api.about.web.HumanElementPayload
import com.afrithecus.brainbox.api.about.web.NarrativePayload
import com.afrithecus.brainbox.api.about.web.PracticalDetailPayload
import com.afrithecus.brainbox.api.about.web.SocialProofPayload
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper

/**
 * product_ops_roadmap item 7: the About payloads both clients fetch (and that the Android
 * AboutScreen and web About page already call) now exist, together with the FAQ feed.
 * They must be readable **without a session**, because both clients load them signed-out.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AboutWebTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
) {

    private fun <T> getAnonymously(path: String, type: Class<T>): T {
        val body = mockMvc.perform(get(path))
            .andExpect(status().isOk)
            .andReturn().response.contentAsString
        return objectMapper.readValue(body, type)
    }

    @Test
    fun `about reads are public and carry the fields the clients bind`() {
        val narrative = getAnonymously("/about/narratives", NarrativePayload::class.java)
        check(narrative.missionStatement.isNotBlank() && narrative.story.isNotBlank() && narrative.vision.isNotBlank())

        val humans = getAnonymously("/about/human-elements", Array<HumanElementPayload>::class.java)
        check(humans.isNotEmpty()) { "the people section must not be empty" }
        check(humans.all { it.founderName.isNotBlank() && it.bio.isNotBlank() })

        val social = getAnonymously("/about/social-proof", SocialProofPayload::class.java)
        check(social.clients.isNotEmpty()) { "testimonies back the landing page" }

        val practical = getAnonymously("/about/practical-details", PracticalDetailPayload::class.java)
        check(practical.contactInfo.isNotBlank() && practical.location.isNotBlank())
    }

    @Test
    fun `faqs are public, versioned and every answer is present`() {
        val faqs = getAnonymously("/about/faqs", FaqPayload::class.java)
        check(faqs.version.isNotBlank()) { "the clients need a version to revalidate their cache" }
        check(faqs.categories.size >= 5) { "expected a useful spread of categories, got " + faqs.categories.size }
        val items = faqs.categories.flatMap { it.items }
        check(items.size >= 15) { "expected a useful number of questions, got " + items.size }
        check(items.all { it.question.isNotBlank() && it.answer.isNotBlank() })
        check(items.map { it.id }.toSet().size == items.size) { "question ids must be unique" }

        // The report policy is user-visible in the FAQ, so it must match what the API enforces.
        val report = items.first { it.id == "student-limit" }
        check(report.answer.contains("ten")) { "the FAQ must state the lifetime allowance" }
        val mass = items.first { it.id == "mass-download" }
        check(mass.answer.contains("80%")) { "the FAQ must state the coverage threshold" }
    }
}
