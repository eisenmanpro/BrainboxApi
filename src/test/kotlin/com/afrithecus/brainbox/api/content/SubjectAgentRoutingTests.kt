package com.afrithecus.brainbox.api.content

import com.afrithecus.brainbox.api.content.ai.AnswerVerificationRequest
import com.afrithecus.brainbox.api.content.ai.AnswerVerificationResult
import com.afrithecus.brainbox.api.content.ai.ContentGenerationProvider
import com.afrithecus.brainbox.api.content.ai.GeneratedStep
import com.afrithecus.brainbox.api.content.ai.GenerationRequest
import com.afrithecus.brainbox.api.content.ai.GenerationResult
import com.afrithecus.brainbox.api.content.entity.ConceptEntity
import com.afrithecus.brainbox.api.content.entity.CurriculumMapEntity
import com.afrithecus.brainbox.api.content.repository.ConceptRepository
import com.afrithecus.brainbox.api.content.repository.CurriculumMapRepository
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
    @Autowired private val concepts: ConceptRepository,
    @Autowired private val curriculumMaps: CurriculumMapRepository,
) {

    @TestConfiguration
    class RecordingProviderConfig {
        @Bean
        @Primary
        fun contentGenerationProvider(): ContentGenerationProvider = RecordingProvider()
    }

    class RecordingProvider : ContentGenerationProvider {
        var lastPersona: String? = null
        var lastCurriculumContext: String? = null

        override val name: String = "recording"

        override fun generate(request: GenerationRequest): GenerationResult {
            lastPersona = request.persona
            lastCurriculumContext = request.curriculumContext
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

    @Test
    fun passesTheCurriculumGroundingResolvedByConceptLookupToTheProvider() {
        val parent = concepts.save(
            ConceptEntity().apply {
                code = "GROUND-STR-" + UUID.randomUUID().toString().take(6)
                name = "Grounding strand"
                subject = "Mathematics"
                sortOrder = 0
            },
        )
        val concept = concepts.save(
            ConceptEntity().apply {
                code = "GROUND-TOP-" + UUID.randomUUID().toString().take(6)
                name = "Grounding topic"
                subject = "Mathematics"
                parentId = parent.id
                sortOrder = 0
            },
        )
        curriculumMaps.save(
            CurriculumMapEntity().apply {
                conceptId = concept.id
                countryCode = "KE"
                curriculum = "CBC"
                gradeLevel = "Grade 4"
                strandName = "Numbers"
                learningOutcome = "Represent simple fractions as parts of a whole."
                sortOrder = 0
            },
        )

        router.resolve(
            request("Mathematics").copy(conceptCode = concept.code),
        )
        val recording = provider as RecordingProvider
        assertTrue(
            recording.lastCurriculumContext?.contains("Represent simple fractions as parts of a whole.") == true,
            recording.lastCurriculumContext,
        )
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
