package com.afrithecus.brainbox.api.content

import com.afrithecus.brainbox.api.content.ai.AnswerVerificationRequest
import com.afrithecus.brainbox.api.content.ai.AnswerVerificationResult
import com.afrithecus.brainbox.api.content.ai.ContentGenerationProvider
import com.afrithecus.brainbox.api.content.ai.GeneratedStep
import com.afrithecus.brainbox.api.content.ai.GenerationRequest
import com.afrithecus.brainbox.api.content.ai.GenerationResult
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.annotation.Transactional
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The router resolves the subject-agent persona from the request subject and hands
 * it to the provider, so a provider only ever has to append it.
 */
@SpringBootTest(properties = ["app.content.run-mode=api"])
@ActiveProfiles("test")
@Transactional
@Import(SubjectAgentRoutingTests.RecordingProviderConfig::class)
class SubjectAgentRoutingTests(
    @Autowired private val router: ContentRouter,
    @Autowired private val provider: ContentGenerationProvider,
) {

    @TestConfiguration
    class RecordingProviderConfig {
        @Bean
        @Primary
        fun contentGenerationProvider(): ContentGenerationProvider = RecordingProvider()
    }

    class RecordingProvider : ContentGenerationProvider {
        var lastPersona: String? = null

        override val name: String = "recording"

        override fun generate(request: GenerationRequest): GenerationResult {
            lastPersona = request.persona
            return GenerationResult(
                body = "Body",
                steps = listOf(GeneratedStep(0, "Step", "Body", null)),
                questions = emptyList(),
                confidence = 0.9,
                model = "recording-model",
                provider = name,
                promptTokens = 1,
                completionTokens = 1,
                costMicros = 1,
            )
        }

        override fun verifyAnswerKeys(request: AnswerVerificationRequest): AnswerVerificationResult =
            AnswerVerificationResult(provider = name)
    }

    @Test
    fun resolvesTheSubjectPersonaForMathematics() {
        router.resolve(request("Mathematics"))
        val recording = provider as RecordingProvider
        assertTrue(recording.lastPersona?.contains("mathematics", ignoreCase = true) == true, recording.lastPersona)
    }

    @Test
    fun fallsBackToTheGeneralPersonaForAnUnknownSubject() {
        router.resolve(request("Creative Arts"))
        val recording = provider as RecordingProvider
        assertTrue(recording.lastPersona?.contains("Kenyan CBC teacher") == true, recording.lastPersona)
    }

    private fun request(subject: String) = GenerationRequest(
        generationKey = "test:subject:" + UUID.randomUUID(),
        taskType = "NOTES",
        subject = subject,
        gradeLevel = "Grade 4",
        language = "en",
        standardVersion = "v1",
    )
}
