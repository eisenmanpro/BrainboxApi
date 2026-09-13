-- ============================================================
-- BrainboxApi V60: doubt question bookmarks
-- Contract: docs/backend_contracts/05_... §3
-- One bookmark row per (user, question); the unique index makes the toggle
-- idempotent under concurrent taps.
-- ============================================================

CREATE TABLE doubt_bookmarks (
    id          uuid PRIMARY KEY,
    user_id     uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    question_id uuid NOT NULL REFERENCES doubt_questions (id) ON DELETE CASCADE,
    created_at  timestamp with time zone NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uq_doubt_bookmark ON doubt_bookmarks (user_id, question_id);
