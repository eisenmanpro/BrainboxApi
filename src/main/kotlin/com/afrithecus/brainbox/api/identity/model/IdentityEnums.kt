package com.afrithecus.brainbox.api.identity.model

/**
 * Canonical role vocabulary. Serialized names must match the Android models and
 * the values stored in the users.role / users.sub_role columns.
 * Contract: docs/backend_contracts/01_AUTH_IDENTITY_AND_ACCESS.md §2.1 / §3.1
 */
enum class Role { STUDENT, TEACHER, PARENT, ADMIN }

/** Sub-roles refine the TEACHER role (doc 01 §3.1). */
enum class SubRole { CTEACHER, GRADE_COORDINATOR, ICT_ADMIN }

/** Roles valid on a device session (doc 01 §5.2). */
enum class SessionRole { STUDENT, TEACHER, PARENT }

/**
 * Whether an account may authenticate. ROSTER_ONLY is a teacher-provisioned
 * learner record for a pupil who has no smartphone: it exists so the traditional
 * exam engine can enter marks, rank and print reports for them, and it can never
 * hold a session until it is upgraded to FULL.
 */
enum class AccountKind { FULL, ROSTER_ONLY }

/** Subscription state machine (doc 01 §4). */
enum class SubscriptionStatus { NONE, ACTIVE, EXPIRED }

/** Subscription tiers (doc 01 §4.1). */
enum class SubscriptionTier { BASE, EXPLORER, PRO }
