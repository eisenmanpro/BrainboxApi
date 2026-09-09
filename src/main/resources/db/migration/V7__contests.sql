-- ============================================================
-- BrainboxApi V7: contests domain
-- Contract: docs/backend_contracts/05_STUDENT_SOCIAL_AND_COMPETITION.md §1
-- Status (UPCOMING/ONGOING/COMPLETED) is derived from the time window;
-- questions reuse the exam question shape & are key-stripped for students.
-- ============================================================

CREATE TABLE contests (
    id               uuid PRIMARY KEY,
    title            varchar(255) NOT NULL,
    subject          varchar(128) NOT NULL,
    grade            varchar(64)  NOT NULL,
    start_time       timestamp with time zone NOT NULL,
    end_time         timestamp with time zone NOT NULL,
    entry_fee        integer NOT NULL DEFAULT 0,
    prize            varchar(255),
    max_participants integer,
    difficulty       integer NOT NULL DEFAULT 3 CHECK (difficulty BETWEEN 1 AND 5),
    lifecycle        varchar(16) NOT NULL DEFAULT 'PUBLISHED'
                     CHECK (lifecycle IN ('DRAFT','PUBLISHED','CANCELLED')),
    created_by       uuid NOT NULL REFERENCES users (id),
    created_at       timestamp with time zone NOT NULL DEFAULT now(),
    updated_at       timestamp with time zone NOT NULL DEFAULT now(),
    version          bigint NOT NULL DEFAULT 0
);

CREATE INDEX ix_contests_window ON contests (start_time, end_time);

CREATE TABLE contest_questions (
    id             uuid PRIMARY KEY,
    contest_id     uuid NOT NULL REFERENCES contests (id) ON DELETE CASCADE,
    text           text NOT NULL,
    q_type         varchar(32) NOT NULL
                   CHECK (q_type IN ('MCQ','MULTI_SELECT','SHORT_ANSWER','ESSAY',
                                     'TRUE_FALSE','MATCHING','FILL_BLANK','NUMBER_ENTRY')),
    options        text,
    correct_answer text,
    explanation    text,
    points         integer NOT NULL DEFAULT 1,
    difficulty     integer NOT NULL DEFAULT 3 CHECK (difficulty BETWEEN 1 AND 5),
    matching_pairs text,
    topic          varchar(255),
    subtopic       varchar(255),
    order_index    integer NOT NULL DEFAULT 0
);

CREATE INDEX ix_contest_questions_contest ON contest_questions (contest_id, order_index);

CREATE TABLE contest_registrations (
    id            uuid PRIMARY KEY,
    contest_id    uuid NOT NULL REFERENCES contests (id) ON DELETE CASCADE,
    student_id    uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    registered_at timestamp with time zone NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uq_contest_registration ON contest_registrations (student_id, contest_id);

CREATE TABLE contest_submissions (
    id                uuid PRIMARY KEY,
    contest_id        uuid NOT NULL REFERENCES contests (id) ON DELETE CASCADE,
    user_id           uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    score             integer NOT NULL DEFAULT 0,
    total_points      integer NOT NULL DEFAULT 0,
    percentage        integer NOT NULL DEFAULT 0,
    correct_count     integer NOT NULL DEFAULT 0,
    question_count    integer NOT NULL DEFAULT 0,
    submitted_at      timestamp with time zone NOT NULL DEFAULT now(),
    answers           text,
    question_results  text,
    created_at        timestamp with time zone NOT NULL DEFAULT now(),
    updated_at        timestamp with time zone NOT NULL DEFAULT now(),
    version           bigint NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX uq_contest_submission_user ON contest_submissions (user_id, contest_id);