-- ============================================================
-- BrainboxApi V19: topic mastery tracking
-- Contract: docs/backend_contracts/03_... §6 + the Android MasteryApi/models.
-- Mastery is cumulative per (user, topic): the server accumulates attempts and
-- recomputes the score; the client renders it read-only.
-- ============================================================

CREATE TABLE topic_mastery (
    id                uuid PRIMARY KEY,
    user_id           uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    topic_id          varchar(160) NOT NULL,
    topic_name        varchar(200) NOT NULL,
    subject           varchar(128) NOT NULL,
    score             double precision NOT NULL DEFAULT 0,
    previous_score    double precision NOT NULL DEFAULT 0,
    attempts_count    integer NOT NULL DEFAULT 0,
    questions_attempted integer NOT NULL DEFAULT 0,
    correct_answers   integer NOT NULL DEFAULT 0,
    total_time_seconds bigint NOT NULL DEFAULT 0,
    last_practiced    timestamp with time zone NOT NULL DEFAULT now(),
    created_at        timestamp with time zone NOT NULL DEFAULT now(),
    updated_at        timestamp with time zone NOT NULL DEFAULT now(),
    version           bigint NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX uq_topic_mastery ON topic_mastery (user_id, topic_id);
CREATE INDEX ix_topic_mastery_user ON topic_mastery (user_id, subject);
