package com.afrithecus.brainbox.api.profile.entity

import com.afrithecus.brainbox.api.common.jpa.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.util.UUID

/**
 * Durable copy of the user profile/settings model (BACKEND_BLUEPRINT §13).
 * The device may cache preferences, but this row is authoritative: the server
 * rebuilds the GET payload from it plus identity/subscription state.
 */
@Entity
@Table(name = "user_settings")
class UserSettingsEntity : BaseEntity() {

    @Column(name = "user_id", nullable = false, updatable = false)
    var userId: UUID = UUID.randomUUID()

    @Column(name = "avatar_url", length = 512)
    var avatarUrl: String? = null

    /** JSON array string of academic subjects. */
    @Column(columnDefinition = "text")
    var subjects: String? = null

    @Column(name = "daily_reminder_enabled", nullable = false)
    var dailyReminderEnabled: Boolean = true

    @Column(name = "daily_reminder_time", length = 16)
    var dailyReminderTime: String? = null

    @Column(name = "weekly_report_enabled", nullable = false)
    var weeklyReportEnabled: Boolean = true

    @Column(name = "preferred_difficulty", nullable = false, length = 32)
    var preferredDifficulty: String = "Medium"

    /** JSON array string of interest subjects. */
    @Column(name = "interest_subjects", columnDefinition = "text")
    var interestSubjects: String? = null

    @Column(name = "show_on_leaderboard", nullable = false)
    var showOnLeaderboard: Boolean = true

    @Column(name = "share_progress_with_school", nullable = false)
    var shareProgressWithSchool: Boolean = true

    @Column(name = "allow_teacher_view", nullable = false)
    var allowTeacherView: Boolean = true

    @Column(name = "push_notifications_enabled", nullable = false)
    var pushNotificationsEnabled: Boolean = true

    @Column(name = "push_contest_reminders", nullable = false)
    var pushContestReminders: Boolean = true

    @Column(name = "push_assignment_due", nullable = false)
    var pushAssignmentDue: Boolean = true

    @Column(name = "push_quiz_results", nullable = false)
    var pushQuizResults: Boolean = true

    @Column(name = "push_weekly_report", nullable = false)
    var pushWeeklyReport: Boolean = true

    @Column(name = "push_badges", nullable = false)
    var pushBadges: Boolean = true

    @Column(name = "sms_reports_enabled", nullable = false)
    var smsReportsEnabled: Boolean = true

    @Column(name = "email_notifications_enabled", nullable = false)
    var emailNotificationsEnabled: Boolean = false

    @Column(name = "mpesa_number", length = 32)
    var mpesaNumber: String? = null

    @Column(nullable = false, length = 16)
    var theme: String = "dark"

    @Column(name = "font_size", nullable = false, length = 16)
    var fontSize: String = "medium"

    @Column(name = "animations_enabled", nullable = false)
    var animationsEnabled: Boolean = true

    @Column(name = "haptic_feedback_enabled", nullable = false)
    var hapticFeedbackEnabled: Boolean = true

    @Column(name = "personalized_content_enabled", nullable = false)
    var personalizedContentEnabled: Boolean = true

    @Column(name = "ai_adaptive_difficulty_enabled", nullable = false)
    var aiAdaptiveDifficultyEnabled: Boolean = true

    @Column(name = "ai_sensitivity", nullable = false)
    var aiSensitivity: Int = 3

    @Column(name = "ai_coaching_style", nullable = false, length = 32)
    var aiCoachingStyle: String = "Encouraging"

    @Column(name = "cbc_pathway", nullable = false, length = 32)
    var cbcPathway: String = "STEM"

    /** JSON object string of competency -> focus score. */
    @Column(name = "competency_focus", columnDefinition = "text")
    var competencyFocus: String? = null

    /** JSON array string of grade levels a teacher handles. */
    @Column(name = "grades_taught", columnDefinition = "text")
    var gradesTaught: String? = null

    @Column(name = "tsc_number", length = 64)
    var tscNumber: String? = null
}
