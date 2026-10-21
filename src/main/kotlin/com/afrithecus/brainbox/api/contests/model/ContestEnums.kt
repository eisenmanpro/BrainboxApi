package com.afrithecus.brainbox.api.contests.model

/** Lifecycle derived from the contest time window (doc 05 §1.1). */
enum class ContestStatus { UPCOMING, ONGOING, COMPLETED }

/** Server-side contest state machine for actions. */
enum class ContestLifecycle { DRAFT, PUBLISHED, CANCELLED }

/**
 * Who may see a contest. Contests are grade-level resources: SCHOOL_GRADE means every class in
 * that school at that grade, never a specific teacher's class list.
 */
enum class ContestScope { GLOBAL, SCHOOL, SCHOOL_GRADE }
