package com.afrithecus.brainbox.api.content

import com.afrithecus.brainbox.api.content.entity.ConceptEntity
import com.afrithecus.brainbox.api.content.entity.ContentUnitEntity
import com.afrithecus.brainbox.api.content.entity.ContentUnitQuestionEntity
import com.afrithecus.brainbox.api.content.entity.ContentUnitStepEntity
import com.afrithecus.brainbox.api.content.entity.CurriculumMapEntity
import com.afrithecus.brainbox.api.content.repository.ConceptRepository
import com.afrithecus.brainbox.api.content.repository.ContentUnitQuestionRepository
import com.afrithecus.brainbox.api.content.repository.ContentUnitRepository
import com.afrithecus.brainbox.api.content.repository.ContentUnitStepRepository
import com.afrithecus.brainbox.api.content.repository.CurriculumMapRepository
import com.afrithecus.brainbox.api.content.repository.ModerationOutcomeRepository
import com.afrithecus.brainbox.api.content.validation.ContentValidationService
import com.afrithecus.brainbox.api.content.validation.FindingSeverity
import com.afrithecus.brainbox.api.content.validation.SafetyValidator
import com.afrithecus.brainbox.api.content.validation.ValidationContext
import com.afrithecus.brainbox.api.content.validation.ValidationFinding
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.core.io.ClassPathResource
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.util.UUID

/**
 * Phase 7.5d: the deterministic, fail-closed safety filter. Proves clean curriculum
 * prose passes, every category code fires on a representative input, all teachable
 * fields (not just the unit body) are scanned, personal data is caught, a match
 * blocks and scores 0.0 through the real validator chain and therefore cannot
 * auto-approve, and a missing or blank blocklist fails closed.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class SafetyValidatorTests(
    @Autowired private val safety: SafetyValidator,
    @Autowired private val validation: ContentValidationService,
    @Autowired private val autoApproval: AutoApprovalService,
    @Autowired private val contentUnits: ContentUnitRepository,
    @Autowired private val steps: ContentUnitStepRepository,
    @Autowired private val questions: ContentUnitQuestionRepository,
    @Autowired private val concepts: ConceptRepository,
    @Autowired private val curriculumMaps: CurriculumMapRepository,
    @Autowired private val outcomes: ModerationOutcomeRepository,
    @Autowired private val mapper: ObjectMapper,
    @Autowired private val entityManager: EntityManager,
) {

    @Test
    fun `safe curriculum prose produces no safety findings`() {
        val samples = listOf(BIOLOGY_TEXT, HISTORY_TEXT, CHEMISTRY_TEXT)
        samples.forEach { text ->
            val findings = safety.validate(context(unit = unit(title = "Curriculum", body = text)))
            check(findings.isEmpty()) {
                "expected no safety findings for legitimate prose, got " + findings.map { it.code } + " in: " + text
            }
        }
    }

    @Test
    fun `each safety category fires on a representative input`() {
        val samples = linkedMapOf(
            "SAFETY_SEXUAL_MINORS" to "He was arrested for sharing child pornography online.",
            "SAFETY_EXPLICIT_SEXUAL" to "The message described an explicit sexual act in detail.",
            "SAFETY_SELF_HARM" to "I want to kill myself tonight.",
            "SAFETY_VIOLENCE_GRAPHIC" to "The clip shows a prisoner being decapitated.",
            "SAFETY_HATE" to "The mob said they would kill all immigrants.",
            "SAFETY_DANGEROUS_INSTRUCTIONS" to "This page explains how to make a bomb at home.",
            "SAFETY_PERSONAL_DATA" to "Contact the teacher on 0712345678.",
        )
        samples.forEach { (code, text) ->
            val findings = safety.validate(context(unit = unit(title = "Sample", body = text)))
            check(findings.any { it.code == code && it.severity == FindingSeverity.BLOCKER }) {
                "expected " + code + ", got " + findings.map { it.code } + " for: " + text
            }
        }
    }

    @Test
    fun `personal data fires on a phone number an email and a national id`() {
        val samples = listOf(
            "Call the head teacher on +254 712 345 678.",
            "Email the office at teacher.help@school.example.com.",
            "National ID: 12345678",
        )
        samples.forEach { text ->
            val findings = safety.validate(context(unit = unit(title = "Contact", body = text)))
            check(findings.any { it.code == "SAFETY_PERSONAL_DATA" }) {
                "expected SAFETY_PERSONAL_DATA, got " + findings.map { it.code } + " for: " + text
            }
        }
    }

    @Test
    fun `a harmful step body and a harmful question are both scanned`() {
        val viaStep = safety.validate(
            context(
                unit = unit(title = "Clean unit", body = "A safe introduction."),
                steps = listOf(step(0, "Safe step", "Here is how to make a bomb.")),
            )
        )
        check(viaStep.any { it.code == "SAFETY_DANGEROUS_INSTRUCTIONS" }) {
            "step body was not scanned: " + viaStep.map { it.code }
        }

        val viaQuestion = safety.validate(
            context(
                unit = unit(title = "Clean unit", body = "A safe introduction."),
                questions = listOf(question("Contact me on 0712345678")),
            )
        )
        check(viaQuestion.any { it.code == "SAFETY_PERSONAL_DATA" }) {
            "question text was not scanned: " + viaQuestion.map { it.code }
        }
    }

    @Test
    fun `options the correct answer and the explanation are scanned`() {
        val viaOption = safety.validate(
            context(unit = unit(title = "Q"), questions = listOf(question("Safe?", listOf("0712345678", "B"), "B")))
        )
        check(viaOption.any { it.code == "SAFETY_PERSONAL_DATA" }) { "option was not scanned" }

        val viaAnswer = safety.validate(
            context(unit = unit(title = "Q"), questions = listOf(question("Safe?", listOf("A", "B"), "teacher@example.com")))
        )
        check(viaAnswer.any { it.code == "SAFETY_PERSONAL_DATA" }) { "correctAnswer was not scanned" }

        val viaExplanation = safety.validate(
            context(
                unit = unit(title = "Q"),
                questions = listOf(question("Safe?", listOf("A", "B"), "A", "Call 0712345678 for help.")),
            )
        )
        check(viaExplanation.any { it.code == "SAFETY_PERSONAL_DATA" }) { "explanation was not scanned" }
    }

    @Test
    fun `a unit that trips safety blocks and scores zero and cannot auto-approve`() {
        val unit = seedAutoApprovableUnit(body = "This lesson explains how to make a bomb at home.")

        val report = validation.validate("UNIT", unit.id)
        check(report.blockers)
        check(report.score == 0.0)
        check(report.findings.any { it.code == "SAFETY_DANGEROUS_INSTRUCTIONS" && it.severity == FindingSeverity.BLOCKER })

        check(autoApproval.maybeAutoApprove("UNIT", unit.id).not()) {
            "a unit that trips the safety gate must never auto-approve"
        }

        entityManager.flush()
        entityManager.clear()
        check(contentUnits.findById(unit.id).orElseThrow().reviewState == "UNREVIEWED")
        check(outcomes.findByContentTypeAndContentIdAndContentVersion("UNIT", unit.id, 1) == null)
    }

    @Test
    fun `a clean unit still scores one and clears the safety gate`() {
        val unit = seedAutoApprovableUnit(body = "A safe introduction to fractions.")

        val report = validation.validate("UNIT", unit.id)
        check(report.findings.none { it.code.startsWith("SAFETY_") }) { "safe content was flagged: " + report.findings }
        check(report.blockers.not())
        check(report.score == 1.0)
    }

    @Test
    fun `a missing blocklist fails closed with a config blocker`() {
        val missing = SafetyValidator(mapper, ClassPathResource("safety/does-not-exist.json"))
        val findings = missing.validate(context(unit = unit(title = "Any", body = "Clean curriculum prose.")))
        check(findings.hasBlocker("SAFETY_CONFIG_MISSING")) { "expected a fail-closed config blocker, got " + findings }
    }

    @Test
    fun `a blank blocklist fails closed with a config blocker`() {
        val blank = SafetyValidator(mapper, ClassPathResource("safety/blank-v1.json"))
        val findings = blank.validate(context(unit = unit(title = "Any", body = "Clean curriculum prose.")))
        check(findings.hasBlocker("SAFETY_CONFIG_MISSING")) { "expected a fail-closed config blocker, got " + findings }
    }

    // ---------------------------------------------------------------- fixtures

    private fun context(
        unit: ContentUnitEntity,
        steps: List<ContentUnitStepEntity> = emptyList(),
        questions: List<ContentUnitQuestionEntity> = emptyList(),
    ): ValidationContext = ValidationContext("UNIT", unit, steps, questions, null, null)

    private fun unit(title: String?, body: String? = "A safe introduction."): ContentUnitEntity =
        ContentUnitEntity().apply {
            generationKey = "test:safety:" + UUID.randomUUID()
            taskType = "NOTES"
            this.title = title
            subject = "Science"
            gradeLevel = "Grade 7"
            language = "en"
            this.body = body
            provenance = "GENERATED"
            reviewState = "UNREVIEWED"
            status = "DRAFT"
        }

    private fun step(orderIndex: Int, title: String?, body: String?): ContentUnitStepEntity =
        ContentUnitStepEntity().apply {
            unitId = UUID.randomUUID()
            this.orderIndex = orderIndex
            this.title = title
            this.body = body
        }

    private fun question(
        text: String,
        options: List<String>? = null,
        answer: String? = null,
        explanation: String? = null,
    ): ContentUnitQuestionEntity =
        ContentUnitQuestionEntity().apply {
            unitId = UUID.randomUUID()
            orderIndex = 0
            qType = "MCQ"
            this.text = text
            this.options = options?.let { mapper.writeValueAsString(it) }
            correctAnswer = answer
            this.explanation = explanation
        }

    /** A unit that clears every other 7.5c gate so any block is the safety gate's. */
    private fun seedAutoApprovableUnit(body: String): ContentUnitEntity {
        val concept = concepts.save(
            ConceptEntity().apply {
                code = "SAF-GRADE7-" + UUID.randomUUID().toString().substring(0, 6)
                name = "Safety fixture"
                subject = "Science"
                sortOrder = 0
            }
        )
        curriculumMaps.save(
            CurriculumMapEntity().apply {
                conceptId = concept.id
                countryCode = "KE"
                curriculum = "CBC"
                gradeLevel = "Grade 7"
            }
        )
        val saved = contentUnits.save(
            unit(title = "Safety integration unit", body = body).apply {
                this.conceptId = concept.id
                this.confidence = 0.95
            }
        )
        val savedSteps = (0 until 3).map { index ->
            steps.save(
                ContentUnitStepEntity().apply {
                    unitId = saved.id
                    orderIndex = index
                    title = "Step " + index
                    this.body = "Safe explanation for step " + index + "."
                }
            )
        }
        repeat(8) { index ->
            questions.save(
                ContentUnitQuestionEntity().apply {
                    unitId = saved.id
                    stepId = if (index == 7) savedSteps.last().id else null
                    orderIndex = index
                    qType = "MCQ"
                    text = "Question " + index + "?"
                    options = mapper.writeValueAsString(listOf("A", "B", "C", "D"))
                    correctAnswer = "A"
                }
            )
        }
        return saved
    }

    private fun List<ValidationFinding>.hasBlocker(code: String): Boolean =
        any { it.code == code && it.severity == FindingSeverity.BLOCKER }

    private companion object {
        const val BIOLOGY_TEXT = "Human reproduction involves the fusion of a sperm cell and an egg cell. " +
            "The male reproductive system includes the penis and the testes. During sexual intercourse, " +
            "semen is released. Puberty causes the body to change, and the female reproductive system " +
            "includes the vagina, uterus and ovaries. Menstruation is part of the menstrual cycle, and " +
            "fertilisation happens in the fallopian tube. A baby develops in the womb during pregnancy."

        const val HISTORY_TEXT = "The Second World War was fought between the Allied and Axis powers from " +
            "1939 to 1945. Major battles such as Stalingrad caused heavy casualties. The war in Europe " +
            "ended after the bombing of Hiroshima and Nagasaki. Soldiers carried rifles, drove tanks and " +
            "flew aircraft. The Holocaust led to the genocide of millions of people, and the League of " +
            "Nations failed to prevent the conflict."

        const val CHEMISTRY_TEXT = "A chemical reaction rearranges atoms to form new substances. Combine " +
            "sodium hydroxide with hydrochloric acid to make sodium chloride and water; the reaction is " +
            "exothermic. Balance the equation so the number of atoms is equal on both sides. Acids react " +
            "with bases in a neutralisation reaction that produces a salt. Always read the safety label " +
            "before heating a substance in the laboratory."
    }
}
