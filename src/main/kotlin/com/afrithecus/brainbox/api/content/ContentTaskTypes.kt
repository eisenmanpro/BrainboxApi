package com.afrithecus.brainbox.api.content

/**
 * Phase 7.5g: the one shared task-type vocabulary for the content pipeline. Before
 * this, each caller kept its own copy of the assessment set, which let a lesson rule
 * drift into the assessment path (the structure validator held a questions-only quiz
 * to the three-step lesson bar). The task type decides which quality rules apply: an
 * assessment is measured by its questions, a lesson by its explained steps.
 *
 * Phase 7.6a adds the two generated content kinds: a PRACTICE_PAPER is an
 * assessment (question floor, independent answer-key verification, marking scheme
 * kept), and a STUDY_GUIDE is a readable lesson (step floor, nested questions
 * optional). Both reuse the universal safety/validator gates unchanged.
 */
object ContentTaskTypes {

    /** Task types where the question count is the product, not the lesson steps. */
    val ASSESSMENT_TYPES = setOf("QUIZ", "EXAM", "ASSESSMENT", "PRACTICE_PAPER")

    /** Readable/lesson task types the BrainBox standard breaks into explained steps. */
    val LESSON_TYPES = setOf("NOTES", "BOOK", "CHUNK", "LESSON", "STUDY_GUIDE")

    /** True when [taskType] is an assessment, case- and whitespace-insensitive. */
    fun isAssessment(taskType: String): Boolean = normalize(taskType) in ASSESSMENT_TYPES

    /** True when [taskType] is a known lesson/readable type, case- and whitespace-insensitive. */
    fun isLesson(taskType: String): Boolean = normalize(taskType) in LESSON_TYPES

    private fun normalize(taskType: String): String = taskType.trim().uppercase()
}
