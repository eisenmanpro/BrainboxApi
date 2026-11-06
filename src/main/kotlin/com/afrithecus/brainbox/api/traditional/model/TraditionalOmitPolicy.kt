package com.afrithecus.brainbox.api.traditional.model

/**
 * What the coordinator instructs the server to do with learners whose paper-exam
 * capture is incomplete when the exam is finalized and analysed.
 *
 * The owner's four options are deliberately separate: "no scores at all" and
 * "some but not all scores" are different operational problems, and replacing a
 * zero with 1 is a data-repair choice rather than an omission.
 */
enum class TraditionalOmitPolicy {
    /** Keep only learners with every expected subject entered; drop the wholly uncaptured. */
    OMIT_NO_SCORES,

    /** Drop learners who are partly captured; keep wholly uncaptured learners at zero. */
    OMIT_INCOMPLETE,

    /** Keep every learner and treat a missing/zero subject as 1 mark for analysis. */
    REPLACE_ZEROS_WITH_ONE,

    /** Default: drop both wholly uncaptured and partly captured learners. */
    OMIT_ALL_INCOMPLETE,
}
