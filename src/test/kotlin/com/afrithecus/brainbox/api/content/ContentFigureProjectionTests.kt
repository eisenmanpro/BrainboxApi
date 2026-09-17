package com.afrithecus.brainbox.api.content

import com.afrithecus.brainbox.api.content.ai.AnswerVerificationRequest
import com.afrithecus.brainbox.api.content.ai.AnswerVerificationResult
import com.afrithecus.brainbox.api.content.ai.ContentGenerationProvider
import com.afrithecus.brainbox.api.content.ai.GeneratedQuestion
import com.afrithecus.brainbox.api.content.ai.GeneratedStep
import com.afrithecus.brainbox.api.content.ai.GenerationRequest
import com.afrithecus.brainbox.api.content.ai.GenerationResult
import com.afrithecus.brainbox.api.learning.model.ContentType
import com.afrithecus.brainbox.api.learning.repository.LearningContentRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Phase 7.5 figures end to end: a model-authored figure spec is rendered to SVG
 * when the unit is persisted, then projected as a DIAGRAM learning block after its
 * step and as a figure object inside the QUIZ metadata.
 */
@SpringBootTest(properties = ["app.content.run-mode=api"])
@ActiveProfiles("test")
@Transactional
@Import(ContentFigureProjectionTests.FigureProviderConfig::class)
class ContentFigureProjectionTests(
    @Autowired private val loop: ContentTaskLoop,
    @Autowired private val jobs: GenerationJobService,
    @Autowired private val projection: ContentProjectionService,
    @Autowired private val contents: LearningContentRepository,
) {

    @TestConfiguration
    class FigureProviderConfig {
        @Bean
        @Primary
        fun contentGenerationProvider(): ContentGenerationProvider = FigureProvider()
    }

    class FigureProvider : ContentGenerationProvider {
        override val name: String = "figure"

        private val mapper = JsonMapper.builder().build()
        private val table: JsonNode = mapper.readTree(
            "{\"kind\":\"TABLE\",\"title\":\"Tens and ones\",\"caption\":\"Place value\"," +
                "\"headers\":[\"Tens\",\"Ones\"],\"rows\":[[\"1\",\"2\"]]}"
        )
        private val bar: JsonNode = mapper.readTree(
            "{\"kind\":\"BAR\",\"caption\":\"Rainfall\",\"categories\":[\"Jan\",\"Feb\"],\"values\":[3,7]}"
        )

        override fun generate(request: GenerationRequest): GenerationResult = GenerationResult(
            body = "Body for " + request.generationKey,
            steps = listOf(
                GeneratedStep(0, "Step one", "First step body", table),
                GeneratedStep(1, "Step two", "Second step body", null),
            ),
            questions = listOf(
                GeneratedQuestion(
                    orderIndex = 0,
                    stepIndex = 1,
                    type = "SHORT_ANSWER",
                    text = "How much rain in February?",
                    correctAnswer = "7",
                    figure = bar,
                ),
            ),
            confidence = 0.95,
            model = name,
            provider = name,
            promptTokens = 1,
            completionTokens = 1,
            costMicros = 1,
        )

        override fun verifyAnswerKeys(request: AnswerVerificationRequest): AnswerVerificationResult =
            AnswerVerificationResult(provider = name)
    }

    @Test
    fun figuresProjectAsDiagramBlocksAndQuizMetadata() {
        val job = jobs.enqueue(request(), GenerationJobSource.USER, null)
        val unit = loop.run(job.id) ?: error("the loop must return the generated unit")

        projection.project(unit.id)

        val blocks = contents.findAllByPostIdOrderByOrderIndexAsc(unit.id)
        assertEquals(
            listOf(ContentType.NOTES, ContentType.DIAGRAM, ContentType.NOTES, ContentType.QUIZ),
            blocks.map { it.contentType },
            blocks.map { it.contentType.name + "@" + it.orderIndex }.toString(),
        )
        assertEquals(listOf(0, 1, 2, 3), blocks.map { it.orderIndex })

        val diagram = blocks[1]
        assertEquals("Step one", diagram.title)
        assertTrue(diagram.content?.contains("<svg") == true, "diagram block must carry rendered SVG")
        assertTrue(diagram.content?.contains("Place value") == true, "the caption is drawn inside the SVG")
        assertTrue(diagram.metadata?.contains("\"caption\":\"Place value\"") == true, diagram.metadata ?: "null")
        assertTrue(diagram.metadata?.contains("\"kind\":\"TABLE\"") == true, diagram.metadata ?: "null")

        val quiz = blocks[3].metadata ?: error("quiz metadata expected")
        assertTrue(quiz.contains("\"type\":\"SVG\""), quiz)
        assertTrue(quiz.contains("Rainfall"), quiz)
        assertTrue(quiz.contains("<svg"), quiz)
        assertTrue(quiz.contains("\"spec\""), quiz)
        assertTrue(quiz.contains("\"kind\":\"BAR\""), quiz)
    }

    private fun request() = GenerationRequest(
        generationKey = "ke:cbc:grade4:mat:figure:" + UUID.randomUUID(),
        taskType = "NOTES",
        subject = "Mathematics",
        gradeLevel = "Grade 4",
        language = "en",
        standardVersion = "v1",
    )
}
