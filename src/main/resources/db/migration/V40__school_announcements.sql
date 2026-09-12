-- ============================================================
-- BrainboxApi V40: school (admin) announcements
-- docs/ongoing/open_gaps.md ANN-1: the admin studio is offline-first, so the
-- CRUD endpoints are the server source of truth for school-wide notices.
-- ============================================================

CREATE TABLE school_announcements (
    id                     uuid PRIMARY KEY,
    school_id              uuid NOT NULL REFERENCES schools (id) ON DELETE CASCADE,
    title                  varchar(200) NOT NULL,
    body                   text NOT NULL,
    audience               varchar(120) NOT NULL,
    posted_by              uuid REFERENCES users (id),
    posted_by_name         varchar(160),
    posted_at              timestamp with time zone NOT NULL,
    scheduled_meeting_date timestamp with time zone,
    meeting_title          varchar(200),
    created_at             timestamp with time zone NOT NULL DEFAULT now(),
    updated_at             timestamp with time zone NOT NULL DEFAULT now(),
    version                bigint NOT NULL DEFAULT 0
);

CREATE INDEX ix_school_announcements_school ON school_announcements (school_id, posted_at);
