package com.afrithecus.brainbox.api.console.web

/**
 * The platform console's request/response shapes. One file, because they are one contract:
 * `docs/ongoing/api_console_changes.md` describes them, and the console app is built against
 * exactly these.
 */

/** One account as the console lists it. */
data class ConsoleUserView(
    val userId: String,
    val name: String,
    val role: String,
    val subRole: String? = null,
    val schoolId: String? = null,
    val schoolName: String? = null,
    val gradeLevel: String? = null,
    val admissionNumber: String? = null,
    val accountKind: String = "FULL",
    val isActive: Boolean,
    val isVerified: Boolean,
    val lastLogin: Long? = null,
    val createdAt: Long = 0,
    val consoleRoleId: String? = null,
    val capabilities: List<String> = emptyList(),
    val subscriptionStatus: String? = null,
    val subscriptionTier: String? = null,
    val subscriptionExpiry: Long? = null,
)

/** Override creation: the operator chooses the type; the server owns the credentials. */
data class ConsoleCreateUserRequest(
    val name: String,
    val role: String,
    val subRole: String? = null,
    val phoneNumber: String? = null,
    val email: String? = null,
    val schoolId: String? = null,
    val gradeLevel: String? = null,
    /** Verify in the same step, for a learner handed a device at the school gate. */
    val verified: Boolean = false,
)

/** A generated credential, returned once at creation or reset and never readable again. */
data class ConsoleCredential(
    val userId: String,
    val name: String,
    /** The phone number or email the account signs in with. */
    val identifier: String,
    val temporaryPassword: String,
    /** How long the operator should treat it as temporary. */
    val expiresInHours: Long,
)

data class ConsoleCreatedUser(
    val user: ConsoleUserView,
    val credential: ConsoleCredential,
    /** The admission number (CTC) the server generated for a learner, if any. */
    val admissionNumber: String? = null,
)

data class ConsoleSetActiveRequest(
    val active: Boolean,
    /** Optional reason, recorded in the audit trail. */
    val reason: String? = null,
)

data class ConsoleSetVerifiedRequest(val verified: Boolean)

/** One school as the platform sees it. */
data class ConsoleSchoolView(
    val schoolId: String,
    val name: String,
    val county: String? = null,
    val location: String? = null,
    val isActive: Boolean,
    val memberCount: Int = 0,
    val createdAt: Long = 0,
)

data class ConsoleCreateSchoolRequest(
    val name: String,
    val county: String? = null,
    val location: String? = null,
    val active: Boolean = true,
)

/** One subscription row. */
data class ConsoleSubscriberView(
    val userId: String,
    val name: String,
    val role: String,
    val schoolId: String? = null,
    val schoolName: String? = null,
    val status: String,
    val tier: String,
    val expiryDate: Long? = null,
    /** True when the subscription is ACTIVE and not past its expiry. */
    val active: Boolean,
    val totalPaid: Int = 0,
)

// --------------------------------------------------------------- notifications

data class ConsoleSendRequest(
    val title: String,
    val body: String,
    /** ALL, ROLE, SCHOOL, GRADE, CLASS or USER. */
    val audienceType: String,
    /** A role name, school id, grade, class id or user id, per audienceType. */
    val audienceValue: String? = null,
    /** Epoch millis; null sends immediately. */
    val scheduledAt: Long? = null,
)

data class ConsoleNotificationView(
    val notificationId: String,
    /** NOTIFICATION (in-app + push) or MESSAGE (an inbox message from Brainbox). */
    val channel: String = "NOTIFICATION",
    val title: String,
    val body: String,
    val audienceType: String,
    val audienceValue: String? = null,
    val scheduledAt: Long? = null,
    val status: String,
    val recipientCount: Int,
    val sentAt: Long? = null,
    val createdAt: Long,
)

data class ConsoleAudiencePreview(
    val audienceType: String,
    val audienceValue: String? = null,
    val recipientCount: Int,
)

data class ConsoleAutomationRuleRequest(
    val name: String,
    /** SUBSCRIPTION_EXPIRING or SUBSCRIPTION_EXPIRED. */
    val triggerType: String,
    val thresholdDays: Int = 7,
    val title: String,
    val body: String,
    val enabled: Boolean = true,
)

data class ConsoleAutomationRuleView(
    val ruleId: String,
    val name: String,
    val triggerType: String,
    val thresholdDays: Int,
    val title: String,
    val body: String,
    val enabled: Boolean,
    val lastRunAt: Long? = null,
)

// -------------------------------------------------------------------- agents

/** One subject agent's effective prompt: the console's override, or the code default. */
data class SubjectAgentPromptView(
    val agentCode: String,
    val displayName: String,
    val subjects: List<String> = emptyList(),
    val persona: String,
    val assessmentGuidance: String,
    val notesGuidance: String,
    /** True when this text came from the console rather than the seeded default. */
    val overridden: Boolean,
    val promptVersion: Int = 0,
    val updatedAt: Long? = null,
)

data class SubjectAgentPromptRequest(
    val persona: String,
    val assessmentGuidance: String = "",
    val notesGuidance: String = "",
)

// ------------------------------------------------------------------ console me

/**
 * What the console needs to render itself for one operator: their capabilities and the
 * **actionable items** those capabilities allow. The console shows this list and nothing else,
 * so a support operator never sees a control they cannot use.
 */
data class ConsoleIdentityPayload(
    val userId: String,
    val name: String,
    val role: String,
    val consoleRoleName: String? = null,
    val capabilities: List<String> = emptyList(),
    val actions: List<ConsoleAction> = emptyList(),
)

/** One thing the operator may do, keyed by a stable id the console maps to a screen/route. */
data class ConsoleAction(
    val id: String,
    val label: String,
    /** The capability that granted it, for display ("because you can…"). */
    val capability: String,
    /** READ or WRITE, so the console can group and style them. */
    val kind: String,
)
