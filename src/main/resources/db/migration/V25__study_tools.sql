-- ============================================================
-- BrainboxApi V25: study tools (doc 03 §9)
-- Server-owned study sessions and server-computed study insights. A unique key
-- on (user, subject, topic, start_time) makes replayed offline sync idempotent.
-- ============================================================

CREATE TABLE study_sessions (
    id               uuid PRIMARY KEY,
    user_id          uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    subject          varchar(128) NOT NULL DEFAULT '',
    topic            varchar(200) NOT NULL DEFAULT '',
    start_time       timestamp with time zone NOT NULL,
    end_time         timestamp with time zone NOT NULL,
    duration_minutes integer NOT NULL DEFAULT 0,
    focus_score      integer NOT NULL DEFAULT 0 CHECK (focus_score BETWEEN 0 AND 100),
    created_at       timestamp with time zone NOT NULL DEFAULT now(),
    updated_at       timestamp with time zone NOT NULL DEFAULT now(),
    version          bigint NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX uq_study_session ON study_sessions (user_id, subject, topic, start_time);
CREATE INDEX ix_study_sessions_user ON study_sessions (user_id, start_time);
