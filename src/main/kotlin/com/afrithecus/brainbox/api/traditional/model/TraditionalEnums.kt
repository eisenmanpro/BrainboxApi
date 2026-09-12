package com.afrithecus.brainbox.api.traditional.model

/** Term in the Kenyan school calendar (doc 10 §1). */
enum class ExamTerm { TERM_1, TERM_2, TERM_3 }

/**
 * Traditional exam lifecycle (doc 10 §1.1).
 * PENDING -> IN_PROGRESS -> CONFIRMED -> PRE_FINAL -> FINALIZED -> PUBLISHED.
 */
enum class TraditionalExamStatus { PENDING, IN_PROGRESS, CONFIRMED, PRE_FINAL, FINALIZED, PUBLISHED }

/** Whether a subject score is standalone or summed from components. */
enum class TraditionalSubjectType { SINGLE, COMBINED }

/** State of a teacher's request to edit a confirmed mark (doc 10 §5). */
enum class TraditionalEditStatus { PENDING, APPROVED, DENIED }

/** Human-readable term label (e.g. "Term 1") used in report payloads. */
fun ExamTerm.displayName(): String = when (this) {
    ExamTerm.TERM_1 -> "Term 1"
    ExamTerm.TERM_2 -> "Term 2"
    ExamTerm.TERM_3 -> "Term 3"
}
