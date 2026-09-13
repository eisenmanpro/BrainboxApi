package com.afrithecus.brainbox.api.content

import com.afrithecus.brainbox.api.content.entity.ConceptEntity
import com.afrithecus.brainbox.api.content.entity.ContentUnitEntity
import com.afrithecus.brainbox.api.content.entity.ContentUnitQuestionEntity
import com.afrithecus.brainbox.api.content.entity.ContentUnitStepEntity
import com.afrithecus.brainbox.api.content.entity.CurriculumMapEntity
import com.afrithecus.brainbox.api.content.entity.GenerationJobEntity
import com.afrithecus.brainbox.api.content.repository.ConceptRepository
import com.afrithecus.brainbox.api.content.repository.ContentUnitQuestionRepository
import com.afrithecus.brainbox.api.content.repository.ContentUnitRepository
import com.afrithecus.brainbox.api.content.repository.ContentUnitStepRepository
import com.afrithecus.brainbox.api.content.repository.CurriculumMapRepository
import com.afrithecus.brainbox.api.content.repository.GenerationJobRepository
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * Phase 7.1 content concept layer + cache schema: the context load validates the
 * V61 DDL against the entities, and this test round-trips one row per table and
 * exercises every repository finder added for the cache.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ContentCacheTests(
    @Autowired private val conceptRepository: ConceptRepository,
    @Autowired private val curriculumMapRepository: CurriculumMapRepository,
    @Autowired private val contentUnitRepository: ContentUnitRepository,
    @Autowired private val contentUnitStepRepository: ContentUnitStepRepository,
    @Autowired private val contentUnitQuestionRepository: ContentUnitQuestionRepository,
    @Autowired private val generationJobRepository: GenerationJobRepository,
    @Autowired private val entityManager: EntityManager,
) {

    @Test
    fun `content concept layer round-trips through the cache schema`() {
        val publishedAt = Instant.parse("2026-01-01T00:00:00Z")
        val runId = UUID.randomUUID()

        val concept = conceptRepository.save(
            ConceptEntity().apply {
                code = "MAT-FRAC-01"
                name = "Fractions"
                description = "Compare, order and compute with fractions."
                subject = "Mathematics"
                sortOrder = 3
            }
        )

        val curriculumRow = curriculumMapRepository.save(
            CurriculumMapEntity().apply {
                conceptId = concept.id
                countryCode = "KE"
                curriculum = "CBC"
                gradeLevel = "Grade 4"
                strandCode = "MAT-NUM"
                strandName = "Numbers"
                substrandCode = "MAT-NUM-FRAC"
                substrandName = "Fractions"
                learningOutcome = "Compare fractions with unlike denominators."
                sortOrder = 1
            }
        )

        val unit = contentUnitRepository.save(
            ContentUnitEntity().apply {
                generationKey = "ke:cbc:grade4:mat-num-frac:lesson:v1"
                taskType = "LESSON"
                conceptId = concept.id
                subject = "Mathematics"
                gradeLevel = "Grade 4"
                language = "en"
                standardVersion = "v1"
                schemaVersion = "v1"
                promptVersion = "prompt-1"
                body = "A lesson about comparing fractions."
                provenance = "GENERATED"
                authorName = "Brainbox"
                sourceUrls = "https://example.org/fractions"
                license = "CC-BY-4.0"
                model = "deepseek-chat"
                tokens = 1234
                confidence = 0.92
                reviewState = "REVIEWED"
                status = "PUBLISHED"
                this.publishedAt = publishedAt
            }
        )

        val step = contentUnitStepRepository.save(
            ContentUnitStepEntity().apply {
                unitId = unit.id
                orderIndex = 0
                title = "Halves and quarters"
                body = "One half is bigger than one quarter."
                figureSvg = "<svg xmlns=\"http://www.w3.org/2000/svg\"></svg>"
                figureUrl = "https://example.org/fraction.svg"
            }
        )

        val question = contentUnitQuestionRepository.save(
            ContentUnitQuestionEntity().apply {
                unitId = unit.id
                stepId = step.id
                orderIndex = 0
                qType = "MULTIPLE_CHOICE"
                text = "Which fraction is larger: 1/2 or 1/4?"
                options = "[\"1/2\",\"1/4\"]"
                correctAnswer = "1/2"
                explanation = "A half is larger than a quarter."
                points = 2
                difficulty = 4
                matchingPairs = null
            }
        )

        val job = generationJobRepository.save(
            GenerationJobEntity().apply {
                generationKey = "ke:cbc:grade4:mat-num-frac:job:1"
                taskType = "LESSON"
                conceptId = concept.id
                gradeLevel = "Grade 4"
                status = "QUEUED"
                attempts = 1
                lastError = null
                this.runId = runId
            }
        )

        // Force the rows through the database and detach them so the reads below
        // are real round-trips rather than persistence-context identity hits.
        entityManager.flush()
        entityManager.clear()

        // concepts
        val storedConcept = conceptRepository.findById(concept.id).orElseThrow()
        check(storedConcept.code == "MAT-FRAC-01")
        check(storedConcept.name == "Fractions")
        check(storedConcept.description == "Compare, order and compute with fractions.")
        check(storedConcept.subject == "Mathematics")
        check(storedConcept.parentId == null)
        check(storedConcept.sortOrder == 3)
        check(storedConcept.createdAt.toEpochMilli() > 0 && storedConcept.updatedAt.toEpochMilli() > 0)
        check(storedConcept.version == 0L)
        check(conceptRepository.findByCode("MAT-FRAC-01")?.id == concept.id)
        check(conceptRepository.findAllBySubjectOrderBySortOrderAsc("Mathematics").any { it.id == concept.id })

        // curriculum_map
        val storedCurriculum = curriculumMapRepository.findById(curriculumRow.id).orElseThrow()
        check(storedCurriculum.conceptId == concept.id)
        check(storedCurriculum.countryCode == "KE")
        check(storedCurriculum.curriculum == "CBC")
        check(storedCurriculum.gradeLevel == "Grade 4")
        check(storedCurriculum.strandCode == "MAT-NUM")
        check(storedCurriculum.strandName == "Numbers")
        check(storedCurriculum.substrandCode == "MAT-NUM-FRAC")
        check(storedCurriculum.substrandName == "Fractions")
        check(storedCurriculum.learningOutcome == "Compare fractions with unlike denominators.")
        check(storedCurriculum.sortOrder == 1)
        check(
            curriculumMapRepository
                .findAllByCountryCodeAndCurriculumAndGradeLevelOrderBySortOrderAsc("KE", "CBC", "Grade 4")
                .any { it.id == curriculumRow.id && it.conceptId == concept.id }
        )

        // content_units
        val storedUnit = contentUnitRepository.findById(unit.id).orElseThrow()
        check(storedUnit.generationKey == unit.generationKey)
        check(storedUnit.taskType == "LESSON")
        check(storedUnit.conceptId == concept.id)
        check(storedUnit.subject == "Mathematics")
        check(storedUnit.gradeLevel == "Grade 4")
        check(storedUnit.language == "en")
        check(storedUnit.standardVersion == "v1")
        check(storedUnit.schemaVersion == "v1")
        check(storedUnit.promptVersion == "prompt-1")
        check(storedUnit.body == "A lesson about comparing fractions.")
        check(storedUnit.provenance == "GENERATED")
        check(storedUnit.authorName == "Brainbox")
        check(storedUnit.sourceUrls == "https://example.org/fractions")
        check(storedUnit.license == "CC-BY-4.0")
        check(storedUnit.model == "deepseek-chat")
        check(storedUnit.tokens == 1234)
        check(storedUnit.confidence != null && kotlin.math.abs(storedUnit.confidence!! - 0.92) < 1e-9)
        check(storedUnit.reviewState == "REVIEWED")
        check(storedUnit.status == "PUBLISHED")
        check(storedUnit.publishedAt == publishedAt)

        val storedByGenerationKey = contentUnitRepository.findByGenerationKey(unit.generationKey)
        check(storedByGenerationKey?.id == unit.id)
        check(
            contentUnitRepository
                .findAllByConceptIdAndGradeLevelAndTaskTypeAndReviewState(concept.id, "Grade 4", "LESSON", "REVIEWED")
                .any { it.id == unit.id }
        )

        // content_unit_steps
        val storedSteps = contentUnitStepRepository.findAllByUnitIdOrderByOrderIndexAsc(unit.id)
        check(storedSteps.size == 1)
        check(storedSteps[0].id == step.id)
        check(storedSteps[0].orderIndex == 0)
        check(storedSteps[0].title == "Halves and quarters")
        check(storedSteps[0].body == "One half is bigger than one quarter.")
        check(storedSteps[0].figureSvg == "<svg xmlns=\"http://www.w3.org/2000/svg\"></svg>")
        check(storedSteps[0].figureUrl == "https://example.org/fraction.svg")

        // content_unit_questions
        val storedQuestions = contentUnitQuestionRepository.findAllByUnitIdOrderByOrderIndexAsc(unit.id)
        check(storedQuestions.size == 1)
        check(storedQuestions[0].id == question.id)
        check(storedQuestions[0].stepId == step.id)
        check(storedQuestions[0].orderIndex == 0)
        check(storedQuestions[0].qType == "MULTIPLE_CHOICE")
        check(storedQuestions[0].text == "Which fraction is larger: 1/2 or 1/4?")
        check(storedQuestions[0].options == "[\"1/2\",\"1/4\"]")
        check(storedQuestions[0].correctAnswer == "1/2")
        check(storedQuestions[0].explanation == "A half is larger than a quarter.")
        check(storedQuestions[0].points == 2)
        check(storedQuestions[0].difficulty == 4)
        check(storedQuestions[0].matchingPairs == null)

        // generation_jobs
        val storedJob = generationJobRepository.findById(job.id).orElseThrow()
        check(storedJob.generationKey == "ke:cbc:grade4:mat-num-frac:job:1")
        check(storedJob.taskType == "LESSON")
        check(storedJob.conceptId == concept.id)
        check(storedJob.gradeLevel == "Grade 4")
        check(storedJob.status == "QUEUED")
        check(storedJob.attempts == 1)
        check(storedJob.lastError == null)
        check(storedJob.runId == runId)
        check(generationJobRepository.findAllByStatusOrderByCreatedAtAsc("QUEUED").any { it.id == job.id })
    }
}
