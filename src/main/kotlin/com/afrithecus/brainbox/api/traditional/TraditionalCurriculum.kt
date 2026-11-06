package com.afrithecus.brainbox.api.traditional

import com.afrithecus.brainbox.api.traditional.model.TraditionalSubjectType
import com.afrithecus.brainbox.api.traditional.web.SubjectConfigDto

/**
 * The Kenyan CBC learning areas that a paper-exam session should offer for a
 * grade, used when a school has not configured its own subject list.
 *
 * Sources: KICD Basic Education Curriculum Framework (2019) learning areas for
 * Lower Primary (Grades 1-3), Upper Primary (Grades 4-6) and Junior Secondary
 * (Grades 7-9), cross-checked against the 2024 KICD reform list. The owner asked
 * for the minimum manual configuration, so a missing school config falls back to
 * these rather than to a hard-coded four-subject list. The school/coordinator
 * config still wins when it exists.
 */
object TraditionalCurriculum {

    private data class Seed(val id: String, val name: String, val maxScore: Int = 100)

    private val lowerPrimary = listOf(
        Seed("eng", "English"),
        Seed("kis", "Kiswahili"),
        Seed("mat", "Mathematics"),
        Seed("env", "Environmental Activities"),
        Seed("hyg", "Hygiene and Nutrition"),
        Seed("re", "Religious Education"),
        Seed("cat", "Creative Arts"),
        Seed("phe", "Physical and Health Education"),
    )

    private val upperPrimary = listOf(
        Seed("eng", "English"),
        Seed("kis", "Kiswahili"),
        Seed("mat", "Mathematics"),
        Seed("sci", "Science and Technology"),
        Seed("soc", "Social Studies"),
        Seed("hsc", "Home Science"),
        Seed("agr", "Agriculture"),
        Seed("re", "Religious Education"),
        Seed("cat", "Creative Arts"),
        Seed("phe", "Physical and Health Education"),
    )

    private val juniorSecondary = listOf(
        Seed("eng", "English"),
        Seed("kis", "Kiswahili"),
        Seed("mat", "Mathematics"),
        Seed("intsci", "Integrated Science"),
        Seed("soc", "Social Studies"),
        Seed("bus", "Business Studies"),
        Seed("agr", "Agriculture"),
        Seed("pretech", "Pre-Technical Studies"),
        Seed("cas", "Creative Arts and Sports"),
        Seed("re", "Religious Education"),
    )

    /**
     * Default subjects for [gradeLevel] (accepts "4", "Grade 4", "grade 4A").
     * Unknown grades fall back to upper-primary because a paper session still
     * needs a usable paper rather than an empty editor.
     */
    fun subjectsFor(gradeLevel: String): List<SubjectConfigDto> {
        val grade = gradeLevel.filter { it.isDigit() }.take(2).toIntOrNull()
        val seeds = when {
            grade != null && grade in 1..3 -> lowerPrimary
            grade != null && grade in 7..9 -> juniorSecondary
            else -> upperPrimary
        }
        return seeds.map {
            SubjectConfigDto(
                subjectId = it.id,
                name = it.name,
                maxScore = it.maxScore,
                type = TraditionalSubjectType.SINGLE,
            )
        }
    }
}
