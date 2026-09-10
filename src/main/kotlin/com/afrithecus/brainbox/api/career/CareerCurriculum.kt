package com.afrithecus.brainbox.api.career

import com.afrithecus.brainbox.api.career.model.CbcGradeBand
import com.afrithecus.brainbox.api.career.model.CbcPathway
import com.afrithecus.brainbox.api.career.model.CbcSubject
import com.afrithecus.brainbox.api.career.model.CareerGoal

/** A subject score used for orientation analysis (subject name + 0-100 score). */
data class SubjectScore(val subject: String, val score: Double)

/** Derived orientation across the four CBC pillars. */
data class OrientationResult(
    val pillars: List<Pillar>,
    val predictedPathway: CbcPathway,
    val insight: String,
    val confidenceScore: Int,
    val maturity: String,
) {
    data class Pillar(val title: String, val score: Int, val subjects: List<CbcSubject>, val color: Long)
}

/**
 * Static CBC curriculum mapping (port of the Android CbcCurriculumMap). The
 * mapping goal -> subjects and the orientation pillars are curriculum facts, so
 * they live in code like the client side; user state and catalogs live in the DB.
 */
object CareerCurriculum {

    val subjectsByBand: Map<CbcGradeBand, List<CbcSubject>> = mapOf(
        CbcGradeBand.LOWER_PRIMARY to listOf(
            CbcSubject.MATHEMATICS, CbcSubject.ENGLISH, CbcSubject.KISWAHILI,
            CbcSubject.SCIENCE_AND_TECHNOLOGY, CbcSubject.SOCIAL_STUDIES, CbcSubject.CREATIVE_ARTS,
            CbcSubject.PHYSICAL_EDUCATION, CbcSubject.RELIGIOUS_EDUCATION,
        ),
        CbcGradeBand.UPPER_PRIMARY to listOf(
            CbcSubject.MATHEMATICS, CbcSubject.ENGLISH, CbcSubject.KISWAHILI,
            CbcSubject.SCIENCE_AND_TECHNOLOGY, CbcSubject.SOCIAL_STUDIES, CbcSubject.CREATIVE_ARTS,
            CbcSubject.PHYSICAL_EDUCATION, CbcSubject.RELIGIOUS_EDUCATION, CbcSubject.AGRICULTURE,
            CbcSubject.HOME_SCIENCE, CbcSubject.COMPUTER_SCIENCE,
        ),
        CbcGradeBand.JUNIOR_SCHOOL to listOf(
            CbcSubject.MATHEMATICS, CbcSubject.ENGLISH, CbcSubject.KISWAHILI,
            CbcSubject.SCIENCE_AND_TECHNOLOGY, CbcSubject.SOCIAL_STUDIES, CbcSubject.CREATIVE_ARTS,
            CbcSubject.PHYSICAL_EDUCATION, CbcSubject.RELIGIOUS_EDUCATION, CbcSubject.AGRICULTURE,
            CbcSubject.HOME_SCIENCE, CbcSubject.COMPUTER_SCIENCE, CbcSubject.BUSINESS_STUDIES,
            CbcSubject.PRE_TECHNICAL_STUDIES, CbcSubject.HEALTH_EDUCATION,
        ),
        CbcGradeBand.SENIOR_SCHOOL to listOf(
            CbcSubject.MATHEMATICS, CbcSubject.ENGLISH, CbcSubject.KISWAHILI, CbcSubject.PHYSICS,
            CbcSubject.CHEMISTRY, CbcSubject.BIOLOGY, CbcSubject.COMPUTER_SCIENCE, CbcSubject.AGRICULTURE,
            CbcSubject.BUSINESS_STUDIES, CbcSubject.HISTORY, CbcSubject.GEOGRAPHY, CbcSubject.CREATIVE_ARTS,
            CbcSubject.PHYSICAL_EDUCATION, CbcSubject.HOME_SCIENCE,
        ),
        CbcGradeBand.POST_SECONDARY to listOf(
            CbcSubject.MATHEMATICS, CbcSubject.COMPUTER_SCIENCE, CbcSubject.BIOLOGY,
            CbcSubject.CHEMISTRY, CbcSubject.PHYSICS, CbcSubject.BUSINESS_STUDIES,
            CbcSubject.AGRICULTURE, CbcSubject.HEALTH_EDUCATION,
        ),
    )

    val subjectsByCareerGoal: Map<CareerGoal, List<CbcSubject>> = mapOf(
        CareerGoal.SOFTWARE_ENGINEER to listOf(CbcSubject.MATHEMATICS, CbcSubject.COMPUTER_SCIENCE, CbcSubject.PHYSICS),
        CareerGoal.DATA_SCIENTIST to listOf(CbcSubject.MATHEMATICS, CbcSubject.COMPUTER_SCIENCE, CbcSubject.PHYSICS),
        CareerGoal.CIVIL_ENGINEER to listOf(CbcSubject.MATHEMATICS, CbcSubject.PHYSICS, CbcSubject.CHEMISTRY),
        CareerGoal.ENGINEER to listOf(CbcSubject.MATHEMATICS, CbcSubject.PHYSICS, CbcSubject.CHEMISTRY),
        CareerGoal.DOCTOR to listOf(CbcSubject.BIOLOGY, CbcSubject.CHEMISTRY, CbcSubject.MATHEMATICS),
        CareerGoal.NURSE to listOf(CbcSubject.BIOLOGY, CbcSubject.CHEMISTRY, CbcSubject.HEALTH_EDUCATION),
        CareerGoal.CLINICAL_OFFICER to listOf(CbcSubject.BIOLOGY, CbcSubject.CHEMISTRY, CbcSubject.HEALTH_EDUCATION),
        CareerGoal.VETERINARIAN to listOf(CbcSubject.BIOLOGY, CbcSubject.CHEMISTRY, CbcSubject.AGRICULTURE),
        CareerGoal.LAWYER to listOf(CbcSubject.ENGLISH, CbcSubject.HISTORY, CbcSubject.SOCIAL_STUDIES),
        CareerGoal.ACCOUNTANT to listOf(CbcSubject.MATHEMATICS, CbcSubject.BUSINESS_STUDIES, CbcSubject.COMPUTER_SCIENCE),
        CareerGoal.ARCHITECT to listOf(CbcSubject.MATHEMATICS, CbcSubject.PHYSICS, CbcSubject.CREATIVE_ARTS),
        CareerGoal.PILOT to listOf(CbcSubject.MATHEMATICS, CbcSubject.PHYSICS, CbcSubject.ENGLISH),
        CareerGoal.AGRICULTURALIST to listOf(CbcSubject.AGRICULTURE, CbcSubject.BIOLOGY, CbcSubject.CHEMISTRY),
        CareerGoal.TEACHER to listOf(CbcSubject.ENGLISH, CbcSubject.KISWAHILI, CbcSubject.SOCIAL_STUDIES),
        CareerGoal.JOURNALIST to listOf(CbcSubject.ENGLISH, CbcSubject.KISWAHILI, CbcSubject.SOCIAL_STUDIES),
        CareerGoal.SOCIAL_WORKER to listOf(CbcSubject.SOCIAL_STUDIES, CbcSubject.ENGLISH, CbcSubject.HEALTH_EDUCATION),
        CareerGoal.ENTREPRENEUR to listOf(CbcSubject.BUSINESS_STUDIES, CbcSubject.MATHEMATICS, CbcSubject.COMPUTER_SCIENCE),
        CareerGoal.ARTIST to listOf(CbcSubject.CREATIVE_ARTS, CbcSubject.ENGLISH, CbcSubject.KISWAHILI),
        CareerGoal.GENERAL to listOf(CbcSubject.MATHEMATICS, CbcSubject.ENGLISH, CbcSubject.SCIENCE_AND_TECHNOLOGY),
    )

    private val pathwayByGoal: Map<CareerGoal, CbcPathway> = mapOf(
        CareerGoal.SOFTWARE_ENGINEER to CbcPathway.STEM,
        CareerGoal.DATA_SCIENTIST to CbcPathway.STEM,
        CareerGoal.CIVIL_ENGINEER to CbcPathway.STEM,
        CareerGoal.ENGINEER to CbcPathway.STEM,
        CareerGoal.DOCTOR to CbcPathway.STEM,
        CareerGoal.NURSE to CbcPathway.STEM,
        CareerGoal.CLINICAL_OFFICER to CbcPathway.STEM,
        CareerGoal.VETERINARIAN to CbcPathway.STEM,
        CareerGoal.ARCHITECT to CbcPathway.STEM,
        CareerGoal.PILOT to CbcPathway.STEM,
        CareerGoal.AGRICULTURALIST to CbcPathway.STEM,
        CareerGoal.ACCOUNTANT to CbcPathway.TECHNICAL_VOCATIONAL,
        CareerGoal.ENTREPRENEUR to CbcPathway.TECHNICAL_VOCATIONAL,
        CareerGoal.LAWYER to CbcPathway.SOCIAL_SCIENCES,
        CareerGoal.JOURNALIST to CbcPathway.SOCIAL_SCIENCES,
        CareerGoal.SOCIAL_WORKER to CbcPathway.SOCIAL_SCIENCES,
        CareerGoal.TEACHER to CbcPathway.SOCIAL_SCIENCES,
        CareerGoal.ARTIST to CbcPathway.ARTS_AND_SPORTS_SCIENCE,
    )

    data class SalaryData(val range: String, val demand: String, val trend: String, val growthPercent: Int)

    private val salaryByGoal: Map<CareerGoal, SalaryData> = mapOf(
        CareerGoal.SOFTWARE_ENGINEER to SalaryData("Ksh 90k - 250k", "High", "Growing", 22),
        CareerGoal.DATA_SCIENTIST to SalaryData("Ksh 100k - 280k", "High", "Growing", 25),
        CareerGoal.DOCTOR to SalaryData("Ksh 150k - 400k", "High", "Growing", 18),
        CareerGoal.NURSE to SalaryData("Ksh 60k - 140k", "High", "Stable", 12),
        CareerGoal.LAWYER to SalaryData("Ksh 80k - 300k", "Moderate", "Stable", 10),
        CareerGoal.ACCOUNTANT to SalaryData("Ksh 70k - 200k", "Moderate", "Stable", 11),
        CareerGoal.ENGINEER to SalaryData("Ksh 80k - 220k", "High", "Growing", 16),
        CareerGoal.CIVIL_ENGINEER to SalaryData("Ksh 80k - 200k", "Moderate", "Growing", 14),
        CareerGoal.PILOT to SalaryData("Ksh 150k - 500k", "Moderate", "Stable", 9),
        CareerGoal.ARCHITECT to SalaryData("Ksh 80k - 220k", "Moderate", "Growing", 13),
        CareerGoal.VETERINARIAN to SalaryData("Ksh 60k - 160k", "Moderate", "Stable", 10),
        CareerGoal.CLINICAL_OFFICER to SalaryData("Ksh 50k - 120k", "High", "Stable", 12),
        CareerGoal.AGRICULTURALIST to SalaryData("Ksh 45k - 150k", "High", "Growing", 15),
        CareerGoal.TEACHER to SalaryData("Ksh 40k - 120k", "High", "Stable", 8),
        CareerGoal.JOURNALIST to SalaryData("Ksh 45k - 150k", "Moderate", "Stable", 7),
        CareerGoal.SOCIAL_WORKER to SalaryData("Ksh 40k - 110k", "Moderate", "Stable", 9),
        CareerGoal.ENTREPRENEUR to SalaryData("Ksh 50k - 500k", "High", "Growing", 20),
        CareerGoal.ARTIST to SalaryData("Ksh 35k - 180k", "Moderate", "Growing", 12),
    )

    private val interviewFocusByGoal: Map<CareerGoal, String> = mapOf(
        CareerGoal.SOFTWARE_ENGINEER to "Problem solving, projects and logical reasoning",
        CareerGoal.DATA_SCIENTIST to "Analytical thinking, statistics and real datasets",
        CareerGoal.DOCTOR to "Empathy, science grounding and ethics",
        CareerGoal.NURSE to "Compassion, teamwork and patient scenarios",
        CareerGoal.LAWYER to "Argumentation, ethics and current affairs",
        CareerGoal.TEACHER to "Communication, patience and subject clarity",
        CareerGoal.JOURNALIST to "Curiosity, communication and current affairs",
        CareerGoal.ACCOUNTANT to "Accuracy, numeracy and integrity",
        CareerGoal.ENGINEER to "Subject mastery and applied problem solving",
        CareerGoal.PILOT to "Focus, discipline and situational judgement",
        CareerGoal.ENTREPRENEUR to "Initiative, resilience and business thinking",
    )

    fun requiredSubjects(goal: CareerGoal): List<CbcSubject> =
        subjectsByCareerGoal[goal] ?: listOf(CbcSubject.GENERAL)

    fun pathwayFor(goal: CareerGoal): CbcPathway? = pathwayByGoal[goal]

    fun salaryFor(goal: CareerGoal): SalaryData =
        salaryByGoal[goal] ?: SalaryData("Ksh 40k - 120k", "Moderate", "Stable", 8)

    fun interviewFocusFor(goal: CareerGoal): String =
        interviewFocusByGoal[goal] ?: "Behavioural and role-specific"

    /** Targets used when a subject has no recorded performance yet. */
    private val targetScores = listOf(85, 80, 90, 75, 80)
    private val defaultScores = listOf(55, 60, 65, 70, 50)
    private val importance = listOf(5, 5, 4, 4, 3)

    data class SkillGap(val skill: String, val userScore: Int, val targetScore: Int, val importance: Int)

    fun skillGaps(goal: CareerGoal, band: CbcGradeBand, realScores: Map<String, Double>): List<SkillGap> {
        val bandSubjects = subjectsByBand[band] ?: return emptyList()
        val relevant = requiredSubjects(goal).filter { it in bandSubjects }
        return relevant.mapIndexed { index, subject ->
            val real = realScores[subject.displayName] ?: realScores[subject.name]
            SkillGap(
                skill = subject.displayName,
                userScore = real?.let { Math.round(it).toInt() } ?: defaultScores.getOrElse(index) { 60 },
                targetScore = targetScores.getOrElse(index) { 80 },
                importance = importance.getOrElse(index) { 4 },
            )
        }
    }

    val pillarSubjectGroups: Map<String, List<CbcSubject>> = linkedMapOf(
        "STEM" to listOf(
            CbcSubject.MATHEMATICS, CbcSubject.SCIENCE_AND_TECHNOLOGY, CbcSubject.COMPUTER_SCIENCE,
            CbcSubject.PHYSICS, CbcSubject.CHEMISTRY, CbcSubject.BIOLOGY, CbcSubject.PRE_TECHNICAL_STUDIES,
        ),
        "Humanities" to listOf(
            CbcSubject.SOCIAL_STUDIES, CbcSubject.RELIGIOUS_EDUCATION, CbcSubject.HISTORY, CbcSubject.GEOGRAPHY,
        ),
        "Linguistic" to listOf(CbcSubject.ENGLISH, CbcSubject.KISWAHILI),
        "Creative & Practical" to listOf(
            CbcSubject.CREATIVE_ARTS, CbcSubject.PHYSICAL_EDUCATION, CbcSubject.AGRICULTURE,
            CbcSubject.HOME_SCIENCE, CbcSubject.HEALTH_EDUCATION, CbcSubject.BUSINESS_STUDIES,
        ),
    )

    private val pillarColors = mapOf(
        "STEM" to 0xFF00E5FF,
        "Humanities" to 0xFFFFD600,
        "Linguistic" to 0xFFFF4081,
        "Creative & Practical" to 0xFF76FF03,
    )

    fun orientation(scores: List<SubjectScore>): OrientationResult {
        val maturity = when {
            scores.isEmpty() -> "COLD_START"
            scores.size < 5 -> "EVOLVING"
            else -> "STABLE"
        }
        val pillars = pillarSubjectGroups.map { (title, subjects) ->
            val names = subjects.map { it.displayName.uppercase() }.toSet()
            val relevant = scores.filter { it.subject.uppercase() in names }
            val score = if (relevant.isNotEmpty()) relevant.map { it.score }.average().toInt()
            else if (maturity == "COLD_START") 50 else 0
            OrientationResult.Pillar(title, score, subjects, pillarColors[title] ?: 0xFF76FF03)
        }
        val top = pillars.maxByOrNull { it.score } ?: pillars.first()
        val predicted = when (top.title) {
            "STEM" -> CbcPathway.STEM
            "Humanities" -> CbcPathway.SOCIAL_SCIENCES
            "Linguistic" -> CbcPathway.ARTS_AND_SPORTS_SCIENCE
            else -> CbcPathway.TECHNICAL_VOCATIONAL
        }
        val insight = when (maturity) {
            "COLD_START" -> "We're waiting for your first academic signals to reveal your natural orientation."
            "EVOLVING" -> "Initial signals suggest a leaning towards " + top.title + ". Keep learning to confirm this path."
            else -> when (top.title) {
                "STEM" -> "Your high performance in analytical subjects shows a natural affinity for technical pathways."
                "Humanities" -> "Your strength in Social Sciences suggests a future in leadership, law, or social impact."
                "Linguistic" -> "Your command of languages and communication is a powerful asset for the arts and media."
                else -> "Your practical and creative skills are highly valued in technical and vocational innovation."
            }
        }
        return OrientationResult(
            pillars = pillars,
            predictedPathway = predicted,
            insight = insight,
            confidenceScore = if (maturity == "COLD_START") 0 else top.score.coerceIn(0, 100),
            maturity = maturity,
        )
    }
}
