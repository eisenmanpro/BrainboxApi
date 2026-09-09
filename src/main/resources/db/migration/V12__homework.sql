-- ============================================================
-- BrainboxApi V12: homework
-- Contract: BrainboxWeb/docs/backend_contract_homework.md
-- Homework PK = client-generated id (hw_<epochMs>); creates are idempotent
-- upserts keyed on that id. JSON list fields stored as text (PG/H2 parity).
-- ============================================================

CREATE TABLE homework (
    id                     varchar(128) PRIMARY KEY,
    class_id               uuid NOT NULL REFERENCES teacher_classes (id),
    teacher_id             uuid NOT NULL REFERENCES users (id),
    teacher_name           varchar(255) NOT NULL,
    school_id              uuid REFERENCES schools (id),
    title                  varchar(255) NOT NULL,
    description            text NOT NULL,
    subject                varchar(128) NOT NULL,
    grade_level            integer NOT NULL,
    due_date               timestamp with time zone NOT NULL,
    submission_type        varchar(32) NOT NULL
                           CHECK (submission_type IN ('FREE_TEXT','PAST_PAPER_REVIEW',
                                                     'EXAM_QUESTION_SET','CHECKLIST',
                                                     'OFFLINE_PHYSICAL_HANDIN')),
    checklist_items        text,
    grading_mode           varchar(32)
                           CHECK (grading_mode IN ('AUTO_IMMEDIATE','AUTO_POST_COMPLETION','MANUAL')),
    is_past_paper_unlocked boolean NOT NULL DEFAULT FALSE,
    cbc_strand_tag         varchar(64),
    cbc_sub_strand_tag     varchar(60),
    assigned_student_ids   text,
    scope                  varchar(32) NOT NULL DEFAULT 'SCHOOL_GRADE_CLASS'
                           CHECK (scope IN ('GLOBAL','SCHOOL_GRADE','SCHOOL_GRADE_CLASS')),
    is_active              boolean NOT NULL DEFAULT TRUE,
    is_draft               boolean NOT NULL DEFAULT FALSE,
    created_at             timestamp with time zone NOT NULL DEFAULT now(),
    updated_at             timestamp with time zone NOT NULL DEFAULT now(),
    version                bigint NOT NULL DEFAULT 0
);

CREATE INDEX ix_homework_class ON homework (class_id, is_active);

CREATE TABLE homework_submissions (
    id             uuid PRIMARY KEY,
    homework_id    varchar(128) NOT NULL REFERENCES homework (id) ON DELETE CASCADE,
    student_id     uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    submission_text text,
    checklist_answers text,
    attachment_url varchar(512),
    status         varchar(16) NOT NULL DEFAULT 'PENDING'
                   CHECK (status IN ('PENDING','GRADED','RETURNED')),
    submitted_at   timestamp with time zone NOT NULL DEFAULT now(),
    grade          integer,
    feedback       text,
    cbc_strand_tag varchar(64),
    graded_by      uuid REFERENCES users (id),
    graded_at      timestamp with time zone,
    created_at     timestamp with time zone NOT NULL DEFAULT now(),
    updated_at     timestamp with time zone NOT NULL DEFAULT now(),
    version        bigint NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX uq_homework_submission ON homework_submissions (homework_id, student_id);
