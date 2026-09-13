-- ============================================================
-- BrainboxApi V44: teacher settings (doc 04 teacher profile/settings)
-- The teacher account itself stays in users; this table holds the app's
-- TeacherSettings fields (preferences, notification switches, weighting).
-- ============================================================

CREATE TABLE teacher_settings (
    id                          uuid PRIMARY KEY,
    teacher_id                  uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    subjects_taught             text,
    tsc_number                  varchar(64),
    default_grade_level         integer,
    language_preference         varchar(16) NOT NULL DEFAULT 'en',
    theme_preference            varchar(16) NOT NULL DEFAULT 'dark',
    auto_attendance             boolean NOT NULL DEFAULT TRUE,
    notifications_enabled       boolean NOT NULL DEFAULT TRUE,
    default_grade_weighting     text,
    assignment_submission_alerts boolean NOT NULL DEFAULT TRUE,
    new_exam_publish            boolean NOT NULL DEFAULT TRUE,
    parent_messages             boolean NOT NULL DEFAULT TRUE,
    staff_bulletin              boolean NOT NULL DEFAULT TRUE,
    email_notifications         boolean NOT NULL DEFAULT FALSE,
    created_at                  timestamp with time zone NOT NULL DEFAULT now(),
    updated_at                  timestamp with time zone NOT NULL DEFAULT now(),
    version                     bigint NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX uq_teacher_settings_teacher ON teacher_settings (teacher_id);
