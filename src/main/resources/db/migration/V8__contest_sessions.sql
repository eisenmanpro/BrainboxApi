-- ============================================================
-- BrainboxApi V8: contest sessions
-- Contract: docs/backend_contracts/05_STUDENT_SOCIAL_AND_COMPETITION.md §1.5
-- One session per (student, contest); window/timer driven by the contest
-- end time (server authoritative).
-- ============================================================

CREATE TABLE contest_sessions (
    id             uuid PRIMARY KEY,
    contest_id     uuid NOT NULL REFERENCES contests (id) ON DELETE CASCADE,
    user_id        uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    status         varchar(16) NOT NULL
                   CHECK (status IN ('IN_PROGRESS','COMPLETED')),
    current_index  integer NOT NULL DEFAULT 0,
    answers        text,
    started_at     timestamp with time zone NOT NULL DEFAULT now(),
    completed_at   timestamp with time zone,
    updated_at     timestamp with time zone NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uq_contest_session_user ON contest_sessions (user_id, contest_id);
