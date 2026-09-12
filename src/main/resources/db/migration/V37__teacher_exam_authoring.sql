-- ============================================================
-- BrainboxApi V37: teacher digital exam authoring, review and remediation
-- Contract: BrainBox/docs/ongoing/api_exams_changes.md
-- The exams domain already owns the exam/question/submission tables; this
-- migration adds the authoring scope, the reusable question bank, the
-- per-question review marks and the class-wide remediation assignments.
-- ============================================================

ALTER TABLE exams ADD COLUMN client_id varchar(80);
ALTER TABLE exams ADD COLUMN class_id uuid;
ALTER TABLE exams ADD COLUMN grade_level integer;
ALTER TABLE exams ADD COLUMN total_points integer NOT NULL DEFAULT 0;
ALTER TABLE exams ADD COLUMN term varchar(16);
ALTER TABLE exams ADD COLUMN open_at timestamp with time zone;
ALTER TABLE exams ADD COLUMN close_at timestamp with time zone;

CREATE UNIQUE INDEX uq_exams_client ON exams (client_id);
CREATE INDEX ix_exams_author ON exams (created_by, created_at);

ALTER TABLE exam_questions ADD COLUMN client_id varchar(80);
ALTER TABLE exam_questions ADD COLUMN section_id varchar(80);
ALTER TABLE exam_questions ADD COLUMN cbc_strand_tag varchar(128);
ALTER TABLE exam_questions ADD COLUMN is_key_question boolean NOT NULL DEFAULT FALSE;
ALTER TABLE exam_questions ADD COLUMN requires_explanation boolean NOT NULL DEFAULT FALSE;
ALTER TABLE exam_questions ADD COLUMN is_from_bank boolean NOT NULL DEFAULT FALSE;

CREATE UNIQUE INDEX uq_exam_question_client ON exam_questions (exam_id, client_id);

CREATE TABLE exam_sections (
    id               uuid PRIMARY KEY,
    exam_id          uuid NOT NULL REFERENCES exams (id) ON DELETE CASCADE,
    client_id        varchar(80) NOT NULL,
    title            varchar(255) NOT NULL,
    instructions     text,
    duration_minutes integer,
    sort_order       integer NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX uq_exam_section_client ON exam_sections (exam_id, client_id);

CREATE TABLE teacher_question_bank (
    id              uuid PRIMARY KEY,
    client_id       varchar(80) NOT NULL,
    teacher_id      uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    school_id       uuid,
    text            text NOT NULL,
    q_type          varchar(32) NOT NULL,
    options         text,
    correct_answer  text,
    explanation     text,
    points          integer NOT NULL DEFAULT 1,
    difficulty      integer NOT NULL DEFAULT 3,
    matching_pairs  text,
    cbc_strand_tag  varchar(128),
    subject         varchar(128),
    grade_level     integer,
    created_at      timestamp with time zone NOT NULL DEFAULT now(),
    updated_at      timestamp with time zone NOT NULL DEFAULT now(),
    version         bigint NOT NULL DEFAULT 0,
    CONSTRAINT ck_question_bank_type CHECK (q_type IN ('MCQ','MULTI_SELECT','SHORT_ANSWER','ESSAY',
                                                      'TRUE_FALSE','MATCHING','FILL_BLANK','NUMBER_ENTRY')),
    CONSTRAINT ck_question_bank_difficulty CHECK (difficulty BETWEEN 1 AND 5)
);

CREATE UNIQUE INDEX uq_question_bank_client ON teacher_question_bank (client_id);
CREATE INDEX ix_question_bank_teacher ON teacher_question_bank (teacher_id);

CREATE TABLE exam_review_marks (
    id            uuid PRIMARY KEY,
    submission_id uuid NOT NULL REFERENCES exam_submissions (id) ON DELETE CASCADE,
    question_id   uuid NOT NULL REFERENCES exam_questions (id) ON DELETE CASCADE,
    mark          integer NOT NULL DEFAULT 0,
    reviewed_by   uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    reviewed_at   timestamp with time zone NOT NULL,
    created_at    timestamp with time zone NOT NULL DEFAULT now(),
    updated_at    timestamp with time zone NOT NULL DEFAULT now(),
    version       bigint NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX uq_exam_review_mark ON exam_review_marks (submission_id, question_id);
CREATE INDEX ix_exam_review_marks_question ON exam_review_marks (question_id);

CREATE TABLE exam_remediations (
    id           uuid PRIMARY KEY,
    exam_id      uuid NOT NULL REFERENCES exams (id) ON DELETE CASCADE,
    cbc_strand   varchar(128) NOT NULL,
    action       varchar(32) NOT NULL,
    assigned_by  uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    assigned_at  timestamp with time zone NOT NULL,
    created_at   timestamp with time zone NOT NULL DEFAULT now(),
    updated_at   timestamp with time zone NOT NULL DEFAULT now(),
    version      bigint NOT NULL DEFAULT 0,
    CONSTRAINT ck_exam_remediation_action CHECK (action IN ('PRACTICE_EXERCISES','EXTRA_LESSON',
                                                            'LEARNING_LAB','MONITOR_ONLY'))
);

CREATE UNIQUE INDEX uq_exam_remediation_strand ON exam_remediations (exam_id, cbc_strand);
