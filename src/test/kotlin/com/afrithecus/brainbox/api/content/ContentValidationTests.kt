package com.afrithecus.brainbox.api.content

import com.afrithecus.brainbox.api.content.entity.ConceptEntity
import com.afrithecus.brainbox.api.content.entity.ContentUnitEntity
import com.afrithecus.brainbox.api.content.entity.ContentUnitQuestionEntity
import com.afrithecus.brainbox.api.content.entity.ContentUnitStepEntity
import com.afrithecus.brainbox.api.content.entity.CurriculumMapEntity
import com.afrithecus.brainbox.api.content.entity.ModerationOutcomeEntity
import com.afrithecus.brainbox.api.content.repository.ConceptRepository
import com.afrithecus.brainbox.api.content.repository.ContentUnitQuestionRepository
import com.afrithecus.brainbox.api.content.repository.ContentUnitRepository
import com.afrithecus.brainbox.api.content.repository.ContentUnitStepRepository
import com.afrithecus.brainbox.api.content.repository.CurriculumMapRepository
import com.afrithecus.brainbox.api.content.repository.ModerationOutcomeRepository
import com.afrithecus.brainbox.api.content.validation.ContentValidationService
import com.afrithecus.brainbox.api.content.validation.FindingSeverity
import com.afrithecus.brainbox.api.content.validation.ValidationReport
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.model.Role
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.util.UUID

/**
 * Phase 7.4b / 7.5c: the validator chain and the machine-first auto-approval bar.
 * Proves a clean unit scores 1.0, each validator catches its own defect, any blocker
 * forces 0.0, and a unit auto-approves only when every 7.5c gate holds: policy on
 * (default), no blockers, score >= 1.0, >= 8 questions, non-null confidence >= 0.90,
 * and an untouched UNREVIEWED state. A machine approval is recorded with
 * auto_approved = true and a null reviewer, and a human decision is never overwritten.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ContentValidationTests(
    @Autowired private val validation: ContentValidationService,
    @Autowired private val autoApproval: AutoApprovalService,
    @Autowired private val policy: ModerationPolicyService,
    @Autowired private val reviewService: ReviewService,
    @Autowired private val contentUnits: ContentUnitRepository,
    @Autowired private val steps: ContentUnitStepRepository,
    @Autowired private val questions: ContentUnitQuestionRepository,
    @Autowired private val concepts: ConceptRepository,
    @Autowired private val curriculumMaps: CurriculumMapRepository,
    @Autowired private val outcomes: ModerationOutcomeRepository,
    @Autowired private val users: UserRepository,
    @Autowired private val mapper: ObjectMapper,
    @Autowired private val entityManager: EntityManager,
) {

    @Test
    fun `a clean unit has no findings and scores 1`() {
        val unit = seedValidUnit()
        val report = validation.validate("UNIT", unit.id)

        check(report.findings.isEmpty()) { "expected no findings, got " + report.findings }
        check(report.blockers.not())
        check(report.score == 1.0)
    }

    @Test
    fun `structure validator blocks a unit without a title and with too few steps`() {
        val unit = contentUnits.save(unit(title = null))
        seedStep(unit.id, 0)
        seedStep(unit.id, 1)

        val report = validation.validate("UNIT", unit.id)
        check(report.blockers)
        check(report.score == 0.0)
        check(report.hasBlocker("STRUCTURE_TITLE_MISSING"))
        check(report.hasBlocker("STRUCTURE_STEPS_TOO_FEW"))
    }

    @Test
    fun `structure validator blocks a step with no body`() {
        val unit = seedValidUnit()
        val blank = steps.findAllByUnitIdOrderByOrderIndexAsc(unit.id).first()
        blank.body = " "
        steps.save(blank)

        val report = validation.validate("UNIT", unit.id)
        check(report.hasBlocker("STRUCTURE_STEP_BODY_MISSING"))
    }

    @Test
    fun `question validator blocks a single-option multiple choice question`() {
        val unit = seedValidUnit()
        seedQuestion(unit.id, null, 9, "MCQ", "Pick one", listOf("A"), "A")

        val report = validation.validate("UNIT", unit.id)
        check(report.hasBlocker("QUESTION_OPTIONS_FEW"))
    }

    @Test
    fun `answer key validator blocks a missing key and a key that is not an option`() {
        val unit = seedValidUnit()
        val finalStep = steps.findAllByUnitIdOrderByOrderIndexAsc(unit.id).last()
        seedQuestion(unit.id, finalStep.id, 10, "MCQ", "No key here", listOf("A", "B"), null)
        seedQuestion(unit.id, finalStep.id, 11, "MCQ", "Bad key", listOf("A", "B"), "Z")

        val report = validation.validate("UNIT", unit.id)
        check(report.hasBlocker("ANSWER_KEY_MISSING"))
        check(report.hasBlocker("ANSWER_KEY_NOT_AN_OPTION"))
    }

    @Test
    fun `curriculum validator blocks a missing concept and a missing mapping`() {
        val noConcept = contentUnits.save(unit(title = "No concept"))
        seedThreeSteps(noConcept.id)
        val noConceptReport = validation.validate("UNIT", noConcept.id)
        check(noConceptReport.hasBlocker("CURRICULUM_CONCEPT_MISSING"))

        val unmapped = contentUnits.save(unit(title = "Unmapped"))
        seedThreeSteps(unmapped.id)
        unmapped.conceptId = concepts.save(concept()).id
        contentUnits.save(unmapped)
        val unmappedReport = validation.validate("UNIT", unmapped.id)
        check(unmappedReport.hasBlocker("CURRICULUM_MAP_MISSING"))
        // CURRICULUM_CONCEPT_UNKNOWN is unreachable through the schema: the
        // content_units.concept_id foreign key makes an orphan concept id impossible.
    }

    @Test
    fun `language validator blocks a unit without language or teachable text`() {
        val unit = seedValidUnit()
        unit.language = " "
        entityManager.flush()
        val report = validation.validate("UNIT", unit.id)
        check(report.hasBlocker("LANGUAGE_MISSING"))
    }

    @Test
    fun `warnings reduce the score without blocking and the score never leaves its bounds`() {
        val unit = seedValidUnit()
        // Remove the questions: a warning (no questions), not a blocker.
        questions.findAllByUnitIdOrderByOrderIndexAsc(unit.id).forEach { questions.delete(it) }
        entityManager.flush()

        val report = validation.validate("UNIT", unit.id)
        check(report.blockers.not())
        check(report.score in 0.0..1.0)
        check(report.score < 1.0)
        check(report.has("STRUCTURE_NO_QUESTIONS", FindingSeverity.WARNING))
    }

    @Test
    fun `auto-approval is enabled by default and approves a clean unit`() {
        check(policy.autoApproveEnabled()) { "auto-approval must be on by default (7.5c)" }
        val unit = seedAutoApprovableUnit()

        check(autoApproval.maybeAutoApprove("UNIT", unit.id))

        entityManager.flush()
        entityManager.clear()
        check(contentUnits.findById(unit.id).orElseThrow().reviewState == "REVIEWED")

        val outcome = outcomes.findByContentTypeAndContentIdAndContentVersion("UNIT", unit.id, 1)!!
        check(outcome.state == "REVIEWED")
        check(outcome.autoApproved)
        check(outcome.reviewerId == null)
        check(outcome.confidenceScore == 1.0)
        check(outcome.quorumRequired == 2)
    }

    @Test
    fun `auto-approval stays off when the policy disables it`() {
        policy.set("auto_approve_enabled", "false")
        val unit = seedAutoApprovableUnit()

        check(autoApproval.maybeAutoApprove("UNIT", unit.id).not())

        entityManager.flush()
        entityManager.clear()
        check(contentUnits.findById(unit.id).orElseThrow().reviewState == "UNREVIEWED")
        check(outcomes.findByContentTypeAndContentIdAndContentVersion("UNIT", unit.id, 1) == null)
    }

    @Test
    fun `auto-approval refuses an assessment with fewer than the minimum questions`() {
        val unit = seedAutoApprovableUnit(questionCount = 7, taskType = "QUIZ")

        check(autoApproval.maybeAutoApprove("UNIT", unit.id).not())

        entityManager.flush()
        entityManager.clear()
        check(contentUnits.findById(unit.id).orElseThrow().reviewState == "UNREVIEWED")
    }

    @Test
    fun `auto-approval approves a NOTES unit with nested questions below the assessment floor`() {
        // A micro-lesson legitimately carries a few nested checks; the 8-question floor is
        // an assessment rule, so this must still auto-approve.
        val unit = seedAutoApprovableUnit(questionCount = 4, taskType = "NOTES")

        check(autoApproval.maybeAutoApprove("UNIT", unit.id))

        entityManager.flush()
        entityManager.clear()
        check(contentUnits.findById(unit.id).orElseThrow().reviewState == "REVIEWED")
    }

    @Test
    fun `auto-approval fails closed when a unit with questions has no confidence`() {
        val unit = seedAutoApprovableUnit(confidence = null)

        check(autoApproval.maybeAutoApprove("UNIT", unit.id).not())

        entityManager.flush()
        entityManager.clear()
        check(contentUnits.findById(unit.id).orElseThrow().reviewState == "UNREVIEWED")
    }

    @Test
    fun `auto-approval refuses blocked content even when enabled`() {
        policy.set("auto_approve_enabled", "true")
        val unit = contentUnits.save(unit(title = null))

        check(autoApproval.maybeAutoApprove("UNIT", unit.id).not())

        entityManager.flush()
        entityManager.clear()
        check(contentUnits.findById(unit.id).orElseThrow().reviewState == "UNREVIEWED")
    }

    @Test
    fun `auto-approval never touches a REVIEWED unit or its human attribution`() {
        val human = seedUser()
        val unit = seedAutoApprovableUnit(reviewState = "REVIEWED")
        outcomes.save(
            ModerationOutcomeEntity().apply {
                contentType = "UNIT"
                contentId = unit.id
                contentVersion = 1
                state = "REVIEWED"
                autoApproved = false
                confidenceScore = null
                reviewerId = human.id
                quorumRequired = 2
                decidedAt = Instant.now()
            }
        )

        check(autoApproval.maybeAutoApprove("UNIT", unit.id).not())

        entityManager.flush()
        entityManager.clear()
        check(contentUnits.findById(unit.id).orElseThrow().reviewState == "REVIEWED")
        val outcome = outcomes.findByContentTypeAndContentIdAndContentVersion("UNIT", unit.id, 1)!!
        check(outcome.autoApproved.not())
        check(outcome.reviewerId == human.id)
        check(outcome.state == "REVIEWED")
    }

    @Test
    fun `a human decision clears the auto-approved marker`() {
        policy.set("auto_approve_enabled", "true")
        val unit = seedAutoApprovableUnit()
        check(autoApproval.maybeAutoApprove("UNIT", unit.id))

        val reviewer = seedUser()
        val outcome = reviewService.recordDecision(
            reviewer, "UNIT", unit.id, 1, "APPROVE", emptyList(), "verified after all"
        )
        check(outcome.autoApproved.not())
        check(outcome.confidenceScore == null)
        check(outcome.reviewerId == null) // one approval does not resolve, so none has resolved it
        check(outcome.state == "UNREVIEWED")

        val second = seedUser()
        val resolved = reviewService.recordDecision(second, "UNIT", unit.id, 1, "APPROVE", emptyList(), "also good")
        check(resolved.state == "REVIEWED")
        check(resolved.reviewerId == second.id)
        check(resolved.autoApproved.not())
        check(resolved.confidenceScore == null)
    }

    // ---------------------------------------------------------------- fixtures

    private fun seedValidUnit(): ContentUnitEntity {
        val concept = concepts.save(concept())
        curriculumMaps.save(
            CurriculumMapEntity().apply {
                conceptId = concept.id
                countryCode = "KE"
                curriculum = "CBC"
                gradeLevel = "Grade 4"
            }
        )
        val unit = contentUnits.save(unit(title = "Fractions made simple", conceptId = concept.id))
        val savedSteps = seedThreeSteps(unit.id)
        seedQuestion(unit.id, savedSteps.last().id, 0, "MCQ", "What is 1/2 + 1/4?", listOf("1/4", "3/4", "1", "2"), "3/4")
        return unit
    }

    /**
     * A fixture that clears every 7.5c gate by default: titled, three explained steps,
     * [questionCount] valid multiple-choice questions (the last attached to the final
     * step), a curriculum mapping and the given critic confidence.
     */
    private fun seedAutoApprovableUnit(
        questionCount: Int = 8,
        confidence: Double? = 0.95,
        reviewState: String = "UNREVIEWED",
        taskType: String = "NOTES",
    ): ContentUnitEntity {
        val concept = concepts.save(concept())
        curriculumMaps.save(
            CurriculumMapEntity().apply {
                conceptId = concept.id
                countryCode = "KE"
                curriculum = "CBC"
                gradeLevel = "Grade 4"
            }
        )
        val unit = contentUnits.save(
            unit(title = "Fractions made simple", conceptId = concept.id).apply {
                this.taskType = taskType
                this.confidence = confidence
                this.reviewState = reviewState
            }
        )
        val savedSteps = seedThreeSteps(unit.id)
        repeat(questionCount) { index ->
            seedQuestion(
                unitId = unit.id,
                stepId = if (index == questionCount - 1) savedSteps.last().id else null,
                orderIndex = index,
                qType = "MCQ",
                text = "Question " + index + "?",
                options = listOf("A", "B", "C", "D"),
                answer = "A",
            )
        }
        return unit
    }

    private fun seedThreeSteps(unitId: UUID): List<ContentUnitStepEntity> =
        (0 until 3).map { seedStep(unitId, it) }

    private fun seedStep(unitId: UUID, orderIndex: Int): ContentUnitStepEntity =
        steps.save(
            ContentUnitStepEntity().apply {
                this.unitId = unitId
                this.orderIndex = orderIndex
                title = "Step " + orderIndex
                body = "Explanation for step " + orderIndex
            }
        )

    private fun seedQuestion(
        unitId: UUID,
        stepId: UUID?,
        orderIndex: Int,
        qType: String,
        text: String,
        options: List<String>?,
        answer: String?,
    ): ContentUnitQuestionEntity =
        questions.save(
            ContentUnitQuestionEntity().apply {
                this.unitId = unitId
                this.stepId = stepId
                this.orderIndex = orderIndex
                this.qType = qType
                this.text = text
                this.options = options?.let { mapper.writeValueAsString(it) }
                correctAnswer = answer
            }
        )

    private fun concept(): ConceptEntity =
        ConceptEntity().apply {
            code = "MATH-FRAC-" + UUID.randomUUID().toString().substring(0, 6)
            name = "Fractions"
            subject = "Mathematics"
            sortOrder = 0
        }

    private fun unit(title: String?, conceptId: UUID? = null): ContentUnitEntity =
        ContentUnitEntity().apply {
            generationKey = "test:validation:" + UUID.randomUUID()
            taskType = "NOTES"
            this.title = title
            this.conceptId = conceptId
            subject = "Mathematics"
            gradeLevel = "Grade 4"
            language = "en"
            body = "An introduction to the concept."
            provenance = "GENERATED"
            reviewState = "UNREVIEWED"
            status = "DRAFT"
        }

    private fun seedUser(): UserEntity {
        val suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10)
        return users.save(
            UserEntity().apply {
                phoneNumber = "07" + suffix.substring(0, 8)
                email = "validation-$suffix@test.brainbox"
                passwordHash = "not-a-real-hash"
                name = "Validation Teacher"
                role = Role.TEACHER
                isActive = true
                isVerified = true
            }
        )
    }

    private fun ValidationReport.has(code: String, severity: FindingSeverity) =
        findings.any { it.code == code && it.severity == severity }

    private fun ValidationReport.hasBlocker(code: String) = has(code, FindingSeverity.BLOCKER)
}
