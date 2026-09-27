package com.afrithecus.brainbox.api.console

import com.afrithecus.brainbox.api.common.jpa.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * A notification composed in the console. The audience is resolved at send time (never stored
 * as a recipient list), so a scheduled message reaches whoever the audience is *then* — which
 * is what "notify every teacher in this school" should mean.
 */
@Entity
@Table(name = "console_notifications")
class ConsoleNotificationEntity : BaseEntity() {

    @Column(nullable = false, length = 200)
    var title: String = ""

    @Column(nullable = false, length = 2000)
    var body: String = ""

    /**
     * The delivery channel: `NOTIFICATION` writes an in-app notification (and pushes), `MESSAGE`
     * writes an inbox message from the platform's own Brainbox account.
     */
    @Column(nullable = false, length = 16)
    var channel: String = "NOTIFICATION"

    /** ALL, ROLE, SCHOOL, GRADE, CLASS or USER. */
    @Column(name = "audience_type", nullable = false, length = 16)
    var audienceType: String = "ALL"

    /** A role name, school id, grade, class id or user id, per [audienceType]. */
    @Column(name = "audience_value", length = 120)
    var audienceValue: String? = null

    /** Null means "send now"; otherwise the scheduler sends it once due. */
    @Column(name = "scheduled_at")
    var scheduledAt: Instant? = null

    /** SCHEDULED, SENT, CANCELLED or FAILED. */
    @Column(nullable = false, length = 16)
    var status: String = "SCHEDULED"

    @Column(name = "recipient_count", nullable = false)
    var recipientCount: Int = 0

    @Column(name = "sent_at")
    var sentAt: Instant? = null

    @Column(name = "created_by")
    var createdBy: UUID? = null
}

/**
 * An automation rule: a trigger the scheduler evaluates and a message it sends. Deliberately a
 * small closed set of triggers rather than a rule engine — each one is computable from the
 * data the API already keeps, so a rule cannot promise something nobody evaluates.
 */
@Entity
@Table(name = "console_automation_rules")
class ConsoleAutomationRuleEntity : BaseEntity() {

    @Column(nullable = false, length = 120)
    var name: String = ""

    /** SUBSCRIPTION_EXPIRING or SUBSCRIPTION_EXPIRED. */
    @Column(name = "trigger_type", nullable = false, length = 32)
    var triggerType: String = "SUBSCRIPTION_EXPIRING"

    /** How many days before expiry the EXPIRING trigger fires. */
    @Column(name = "threshold_days", nullable = false)
    var thresholdDays: Int = 7

    @Column(nullable = false, length = 200)
    var title: String = ""

    @Column(nullable = false, length = 2000)
    var body: String = ""

    @Column(nullable = false)
    var enabled: Boolean = true

    @Column(name = "last_run_at")
    var lastRunAt: Instant? = null

    @Column(name = "created_by")
    var createdBy: UUID? = null
}

/**
 * A console edit of one subject agent's prompt. The registry in code is the default; a row here
 * overrides it, versioned so a change is traceable and a bad prompt can be rolled back by
 * writing the previous text.
 */
@Entity
@Table(name = "subject_agent_prompts")
class SubjectAgentPromptEntity : BaseEntity() {

    @Column(name = "agent_code", nullable = false, unique = true, length = 32)
    var agentCode: String = ""

    @Column(nullable = false, length = 4000)
    var persona: String = ""

    @Column(name = "assessment_guidance", nullable = false, length = 2000)
    var assessmentGuidance: String = ""

    @Column(name = "notes_guidance", nullable = false, length = 2000)
    var notesGuidance: String = ""

    /**
     * Bumped on every write, so the router's capture can say which prompt text was used.
     * (Distinct from the entity's optimistic-locking `version`.)
     */
    @Column(name = "prompt_version", nullable = false)
    var promptVersion: Int = 1

    @Column(name = "updated_by")
    var updatedBy: UUID? = null
}
