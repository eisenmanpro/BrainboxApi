-- ============================================================
-- BrainboxApi V16: user profile settings
-- Contract: BACKEND_BLUEPRINT.md §13 (GET/PATCH /profile/settings/{userId})
-- Mirrors the Android UserSettings model 1:1; every preference is stored so the
-- server (not the device) owns the durable copy.
-- ============================================================

CREATE TABLE user_settings (
    id                             uuid PRIMARY KEY,
    user_id                        uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    avatar_url                     varchar(512),
    subjects                       text,
    daily_reminder_enabled         boolean NOT NULL DEFAULT TRUE,
    daily_reminder_time            varchar(16),
    weekly_report_enabled          boolean NOT NULL DEFAULT TRUE,
    preferred_difficulty           varchar(32) NOT NULL DEFAULT 'Medium',
    interest_subjects              text,
    show_on_leaderboard            boolean NOT NULL DEFAULT TRUE,
    share_progress_with_school     boolean NOT NULL DEFAULT TRUE,
    allow_teacher_view             boolean NOT NULL DEFAULT TRUE,
    push_notifications_enabled     boolean NOT NULL DEFAULT TRUE,
    push_contest_reminders         boolean NOT NULL DEFAULT TRUE,
    push_assignment_due            boolean NOT NULL DEFAULT TRUE,
    push_quiz_results              boolean NOT NULL DEFAULT TRUE,
    push_weekly_report             boolean NOT NULL DEFAULT TRUE,
    push_badges                    boolean NOT NULL DEFAULT TRUE,
    sms_reports_enabled            boolean NOT NULL DEFAULT TRUE,
    email_notifications_enabled    boolean NOT NULL DEFAULT FALSE,
    mpesa_number                   varchar(32),
    theme                          varchar(16) NOT NULL DEFAULT 'dark',
    font_size                      varchar(16) NOT NULL DEFAULT 'medium',
    animations_enabled             boolean NOT NULL DEFAULT TRUE,
    haptic_feedback_enabled        boolean NOT NULL DEFAULT TRUE,
    personalized_content_enabled   boolean NOT NULL DEFAULT TRUE,
    ai_adaptive_difficulty_enabled boolean NOT NULL DEFAULT TRUE,
    ai_sensitivity                 integer NOT NULL DEFAULT 3 CHECK (ai_sensitivity BETWEEN 1 AND 5),
    ai_coaching_style              varchar(32) NOT NULL DEFAULT 'Encouraging',
    cbc_pathway                    varchar(32) NOT NULL DEFAULT 'STEM',
    competency_focus               text,
    grades_taught                  text,
    tsc_number                     varchar(64),
    created_at                     timestamp with time zone NOT NULL DEFAULT now(),
    updated_at                     timestamp with time zone NOT NULL DEFAULT now(),
    version                        bigint NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX uq_user_settings_user ON user_settings (user_id);
