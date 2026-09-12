-- ============================================================
-- BrainboxApi V36: teacher announcements
-- Contract: BrainBox/docs/ongoing/api_announcement_changes.md (doc 04 section 6)
-- ============================================================

CREATE TABLE teacher_announcements (
    id                 uuid PRIMARY KEY,
    client_id          varchar(80) NOT NULL,
    teacher_id         uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    school_id          uuid,
    title              varchar(200) NOT NULL,
    content            text NOT NULL,
    announcement_type  varchar(16) NOT NULL,
    audience           varchar(16) NOT NULL,
    target_class_ids   text,
    target_grade_levels text,
    target_student_ids text,
    is_priority        boolean NOT NULL DEFAULT FALSE,
    sent_at            timestamp with time zone NOT NULL,
    scheduled_at       timestamp with time zone,
    expires_at         timestamp with time zone,
    delivered_at       timestamp with time zone,
    created_at         timestamp with time zone NOT NULL DEFAULT now(),
    updated_at         timestamp with time zone NOT NULL DEFAULT now(),
    version            bigint NOT NULL DEFAULT 0,
    CONSTRAINT ck_announcement_type CHECK (announcement_type IN ('NOTICE','EVENT','EXAM','MEETING')),
    CONSTRAINT ck_announcement_audience CHECK (audience IN ('CLASS','GRADE','SCHOOL','TEACHER','PARENT'))
);

CREATE UNIQUE INDEX uq_teacher_announcement_client ON teacher_announcements (client_id);
CREATE INDEX ix_teacher_announcements_teacher ON teacher_announcements (teacher_id, sent_at);
CREATE INDEX ix_teacher_announcements_due ON teacher_announcements (delivered_at, scheduled_at);

CREATE TABLE announcement_views (
    id               uuid PRIMARY KEY,
    announcement_id  uuid NOT NULL REFERENCES teacher_announcements (id) ON DELETE CASCADE,
    student_id       uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    viewed_at        timestamp with time zone,
    acknowledged_at  timestamp with time zone
);

CREATE UNIQUE INDEX uq_announcement_view ON announcement_views (announcement_id, student_id);
