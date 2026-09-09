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

/** Subscription state machine (doc 01 §4). */
enum class SubscriptionStatus { NONE, ACTIVE, EXPIRED }

/** Subscription tiers (doc 01 §4.1). */
enum class SubscriptionTier { BASE, EXPLORER, PRO }
