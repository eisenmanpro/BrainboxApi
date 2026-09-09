-- ============================================================
-- BrainboxApi V15: doubt solving forum
-- Contract: docs/backend_contracts/05_... §3
-- ============================================================

CREATE TABLE doubt_questions (
    id         uuid PRIMARY KEY,
    title      varchar(255) NOT NULL,
    body       text NOT NULL,
    subject    varchar(128) NOT NULL,
    tags       text,
    author_id  uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    status     varchar(16) NOT NULL DEFAULT 'OPEN'
               CHECK (status IN ('OPEN','ANSWERED','CLOSED')),
    vote_count integer NOT NULL DEFAULT 0,
    view_count integer NOT NULL DEFAULT 0,
    created_at timestamp with time zone NOT NULL DEFAULT now(),
    updated_at timestamp with time zone NOT NULL DEFAULT now(),
    version    bigint NOT NULL DEFAULT 0
);

CREATE TABLE doubt_answers (
    id          uuid PRIMARY KEY,
    question_id uuid NOT NULL REFERENCES doubt_questions (id) ON DELETE CASCADE,
    body        text NOT NULL,
    author_id   uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    author_role varchar(16) NOT NULL CHECK (author_role IN ('TEACHER','STUDENT')),
    is_accepted boolean NOT NULL DEFAULT FALSE,
    vote_count  integer NOT NULL DEFAULT 0,
    created_at  timestamp with time zone NOT NULL DEFAULT now(),
    updated_at  timestamp with time zone NOT NULL DEFAULT now()
);

CREATE INDEX ix_doubt_answers_question ON doubt_answers (question_id, created_at);

CREATE TABLE doubt_votes (
    id          uuid PRIMARY KEY,
    voter_id    uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    target_type varchar(8) NOT NULL CHECK (target_type IN ('question','answer')),
    target_id   uuid NOT NULL,
    direction   integer NOT NULL CHECK (direction IN (-1, 1)),
    created_at  timestamp with time zone NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uq_doubt_vote ON doubt_votes (voter_id, target_type, target_id);
