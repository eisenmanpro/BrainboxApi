package com.afrithecus.brainbox.api.contests.model

/** Lifecycle derived from the contest time window (doc 05 §1.1). */
enum class ContestStatus { UPCOMING, ONGOING, COMPLETED }

/** Server-side contest state machine for actions. */
enum class ContestLifecycle { DRAFT, PUBLISHED, CANCELLED }
