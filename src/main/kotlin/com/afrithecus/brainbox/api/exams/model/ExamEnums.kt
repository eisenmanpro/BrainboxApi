package com.afrithecus.brainbox.api.exams.model

/** Exam types (doc 02 §1.2). */
enum class ExamType { DIGITAL, PRACTICE_PAPER, TRADITIONAL, QUIZ }

/** Content scope rules shared with the learning hub (ARCHITECTURE.md §A.2). */
enum class ExamScope { GLOBAL, SCHOOL, SCHOOL_GRADE_CLASS }

/** Lifecycle for authored exams (doc 02 §2.4). */
enum class ExamStatus { DRAFT, PUBLISHED, ARCHIVED }

/** Question shapes (doc 02 §4.1). */
enum class QuestionType {
    MCQ, MULTI_SELECT, SHORT_ANSWER, ESSAY, TRUE_FALSE, MATCHING, FILL_BLANK, NUMBER_ENTRY,
}

/** Per-user exam session state (doc 02 §3.1). */
enum class SessionStatus { IN_PROGRESS, COMPLETED }
