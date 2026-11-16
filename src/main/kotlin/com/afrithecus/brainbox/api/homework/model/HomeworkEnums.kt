package com.afrithecus.brainbox.api.homework.model

/** Submission shapes (web homework contract). */
enum class SubmissionType { FREE_TEXT, PRACTICE_PAPER_REVIEW, EXAM_QUESTION_SET, CHECKLIST, OFFLINE_PHYSICAL_HANDIN }

/** How results release; AUTO modes arrive with question-set homework. */
enum class GradingMode { AUTO_IMMEDIATE, AUTO_POST_COMPLETION, MANUAL }

/** Assignment scope. */
enum class HomeworkScope { GLOBAL, SCHOOL_GRADE, SCHOOL_GRADE_CLASS }

/**
 * Homework category. SPECIALIZED is a targeted intervention assignment created
 * for named learners with a stated focus; it uses a normal submission type so the
 * learner experience is unchanged. Persisted in homework.kind (V27).
 */
enum class HomeworkKind { REGULAR, SPECIALIZED }

/** Submission lifecycle. */
enum class SubmissionStatus { PENDING, GRADED, RETURNED }
