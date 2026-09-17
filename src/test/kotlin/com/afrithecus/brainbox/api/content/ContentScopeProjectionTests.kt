package com.afrithecus.brainbox.api.content

import com.afrithecus.brainbox.api.content.ai.AnswerVerificationRequest
import com.afrithecus.brainbox.api.content.ai.AnswerVerificationResult
import com.afrithecus.brainbox.api.content.ai.ContentGenerationProvider
import com.afrithecus.brainbox.api.content.ai.GeneratedStep
import com.afrithecus.brainbox.api.content.ai.GenerationRequest
import com.afrithecus.brainbox.api.content.ai.GenerationResult
import com.afrithecus.brainbox.api.identity.entity.SchoolEntity
import com.afrithecus.brainbox.api.identity.repository.SchoolRepository
import com.afrithecus.brainbox.api.learning.model.LearningScope
import com.afrithecus.brainbox.api.learning.repository.LearningPostRepository
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
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Phase 7.5 scope inheritance: a request scope reaches the content unit and then
 * the projected learner row, while an unscoped request stays GLOBAL even when the
 * job carries a tenant.
 */
@SpringBootTest(properties = ["app.content.run-mode=api"])
@ActiveProfiles("test")
@Transactional
@Import(ContentScopeProjectionTests.ScopeProviderConfig::class)
class ContentScopeProjectionTests(
    @Autowired private val loop: ContentTaskLoop,
    @Autowired private val jobs: GenerationJobService,
    @Autowired private val projection: ContentProjectionService,
    @Autowired private val posts: LearningPostRepository,
    @Autowired private val schools: SchoolRepository,
    @Autowired private val provider: ContentGenerationProvider,
) {

    @TestConfiguration
    class ScopeProviderConfig {
        @Bean
        @Primary
        fun contentGenerationProvider(): ContentGenerationProvider = ScopeProvider()
    }

    class ScopeProvider : ContentGenerationProvider {
        override val name: String = "scope"

        override fun generate(request: GenerationRequest): GenerationResult = GenerationResult(
            body = "Body for " + request.generationKey,
            steps = listOf(
                GeneratedStep(0, "Step one", "First step body", null),
                GeneratedStep(1, "Step two", "Second step body", null),
                GeneratedStep(2, "Step three", "Third step body", null),
            ),
            questions = emptyList(),
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
    fun schoolScopedRequestProjectsASchoolScopedRow() {
        val school = school()
        val job = jobs.enqueue(request("SCHOOL"), GenerationJobSource.USER, school.id)
        val unit = loop.run(job.id) ?: error("the loop must return the generated unit")

        assertEquals(LearningScope.SCHOOL, unit.scope)
        assertEquals(school.id, unit.schoolId)
        assertEquals(com.afrithecus.brainbox.api.content.schema.ContentSchemaV1.VERSION, unit.contentSchemaVersion)

        projection.project(unit.id)
        val post = posts.findById(unit.id).orElseThrow()
        assertEquals(LearningScope.SCHOOL, post.scope)
        assertEquals(school.id, post.schoolId)
    }

    @Test
    fun unscopedRequestStaysGlobalEvenWhenTheJobHasATenant() {
        val school = school()
        val job = jobs.enqueue(request(null), GenerationJobSource.USER, school.id)
        val unit = loop.run(job.id) ?: error("the loop must return the generated unit")

        assertEquals(LearningScope.GLOBAL, unit.scope)
        assertNull(unit.schoolId)
    }

    private fun school(): SchoolEntity = schools.save(
        SchoolEntity().apply {
            name = "Scope School " + UUID.randomUUID().toString().take(6)
            isActive = true
        },
    )

    private fun request(scope: String?) = GenerationRequest(
        generationKey = "ke:cbc:grade4:mat:scope:" + UUID.randomUUID(),
        taskType = "NOTES",
        subject = "Mathematics",
        gradeLevel = "Grade 4",
        language = "en",
        standardVersion = "v1",
        scope = scope,
    )
}
