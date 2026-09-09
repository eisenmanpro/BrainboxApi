-- ============================================================
-- BrainboxApi V6: digital exams domain
-- Contract: docs/backend_contracts/02_EXAMS_AND_ASSESSMENTS.md §1-§5
-- Answer keys/options live server-side only; student-facing payloads are
-- stripped by the API layer. JSON lists (options, matchingPairs, answers,
-- questionResults) are stored as text columns for Postgres/H2 parity.
-- ============================================================

CREATE TABLE exams (
    id               uuid PRIMARY KEY,
    title            varchar(255) NOT NULL,
    subject          varchar(128) NOT NULL,
    exam_type        varchar(32)  NOT NULL
                     CHECK (exam_type IN ('DIGITAL','PAST_PAPER','TRADITIONAL','QUIZ')),
    scope            varchar(32)  NOT NULL DEFAULT 'GLOBAL'
                     CHECK (scope IN ('GLOBAL','SCHOOL','SCHOOL_GRADE_CLASS')),
    school_id        uuid REFERENCES schools (id),
    duration_minutes integer NOT NULL,
    question_count   integer NOT NULL DEFAULT 0,
    difficulty       integer NOT NULL DEFAULT 3 CHECK (difficulty BETWEEN 1 AND 5),
    status           varchar(16) NOT NULL DEFAULT 'DRAFT'
                     CHECK (status IN ('DRAFT','PUBLISHED','ARCHIVED')),
    exam_year        integer,
    is_mcp           boolean NOT NULL DEFAULT FALSE,
    cover_image_url  varchar(512),
    created_by       uuid NOT NULL REFERENCES users (id),
    created_at       timestamp with time zone NOT NULL DEFAULT now(),
    updated_at       timestamp with time zone NOT NULL DEFAULT now(),
    version          bigint NOT NULL DEFAULT 0
);

CREATE INDEX ix_exams_scope_status ON exams (scope, status);
CREATE INDEX ix_exams_subject ON exams (subject);

CREATE TABLE exam_questions (
    id             uuid PRIMARY KEY,
    exam_id        uuid NOT NULL REFERENCES exams (id) ON DELETE CASCADE,
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

CREATE INDEX ix_questions_exam ON exam_questions (exam_id, order_index);

CREATE TABLE exam_sessions (
    id                    uuid PRIMARY KEY,
    exam_id               uuid NOT NULL REFERENCES exams (id) ON DELETE CASCADE,
    user_id               uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    status                varchar(16) NOT NULL
                          CHECK (status IN ('IN_PROGRESS','COMPLETED')),
    current_index         integer NOT NULL DEFAULT 0,
    answers               text,
    flagged               text,
    started_at            timestamp with time zone NOT NULL DEFAULT now(),
    completed_at          timestamp with time zone,
    updated_at            timestamp with time zone NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uq_exam_session_user_exam ON exam_sessions (user_id, exam_id);

CREATE TABLE exam_submissions (
    id               uuid PRIMARY KEY,
    exam_id          uuid NOT NULL REFERENCES exams (id) ON DELETE CASCADE,
    user_id          uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    score            integer NOT NULL DEFAULT 0,
    total_points     integer NOT NULL DEFAULT 0,
    percentage       integer NOT NULL DEFAULT 0,
    grade            varchar(16),
    correct_count    integer NOT NULL DEFAULT 0,
    question_count   integer NOT NULL DEFAULT 0,
    time_taken_seconds integer NOT NULL DEFAULT 0,
    submitted_at     timestamp with time zone NOT NULL DEFAULT now(),
    answers          text,
    question_results text,
    created_at       timestamp with time zone NOT NULL DEFAULT now(),
    updated_at       timestamp with time zone NOT NULL DEFAULT now(),
    version          bigint NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX uq_exam_submission_user_exam ON exam_submissions (user_id, exam_id);
CREATE INDEX ix_submissions_exam ON exam_submissions (exam_id);
