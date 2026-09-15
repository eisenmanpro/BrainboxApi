package com.afrithecus.brainbox.api.homework.model

/** Submission shapes (web homework contract). */
enum class SubmissionType { FREE_TEXT, PRACTICE_PAPER_REVIEW, EXAM_QUESTION_SET, CHECKLIST, OFFLINE_PHYSICAL_HANDIN }

/** How results release; AUTO modes arrive with question-set homework. */
enum class GradingMode { AUTO_IMMEDIATE, AUTO_POST_COMPLETION, MANUAL }

/** Assignment scope. */
enum class HomeworkScope { GLOBAL, SCHOOL_GRADE, SCHOOL_GRADE_CLASS }

/** Submission lifecycle. */
enum class SubmissionStatus { PENDING, GRADED, RETURNED }
