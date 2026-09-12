-- ============================================================
-- BrainboxApi V29: traditional (paper) exam engine + student reports
-- Contract: docs/backend_contracts/10_TRADITIONAL_EXAMS_GRADEBOOKS_AND_ANALYTICS.md,
--           docs/ongoing/api_student_reports_changes.md
-- ============================================================

CREATE TABLE traditional_exams (
    id                  uuid PRIMARY KEY,
    title               varchar(255) NOT NULL,
    term                varchar(16) NOT NULL,
    grade_level         varchar(64) NOT NULL,
    exam_year           integer NOT NULL,
    status              varchar(32) NOT NULL,
    max_score           integer NOT NULL DEFAULT 100,
    auto_generated      boolean NOT NULL DEFAULT TRUE,
    school_id           uuid,
    created_by          uuid NOT NULL,
    finalized_at        timestamp with time zone,
    finalized_by        uuid,
    coordinator_remarks text,
    published_at        timestamp with time zone,
    published_by        uuid,
    created_at          timestamp with time zone NOT NULL DEFAULT now(),
    updated_at          timestamp with time zone NOT NULL DEFAULT now(),
    version             bigint NOT NULL DEFAULT 0,
    CONSTRAINT ck_traditional_exam_status CHECK (status IN ('PENDING','IN_PROGRESS','CONFIRMED','PRE_FINAL','FINALIZED','PUBLISHED')),
    CONSTRAINT ck_traditional_exam_term CHECK (term IN ('TERM_1','TERM_2','TERM_3'))
);

CREATE INDEX ix_traditional_exams_scope ON traditional_exams (school_id, grade_level, term, exam_year);
CREATE INDEX ix_traditional_exams_status ON traditional_exams (status, published_at);

CREATE TABLE traditional_exam_subjects (
    id           uuid PRIMARY KEY,
    exam_id      uuid NOT NULL REFERENCES traditional_exams (id) ON DELETE CASCADE,
    subject_id   varchar(64) NOT NULL,
    name         varchar(128) NOT NULL,
    max_score    integer NOT NULL,
    subject_type varchar(16) NOT NULL DEFAULT 'SINGLE',
    is_optional  boolean NOT NULL DEFAULT FALSE,
    components   text,
    order_index  integer NOT NULL DEFAULT 0,
    CONSTRAINT ck_traditional_subject_type CHECK (subject_type IN ('SINGLE','COMBINED')),
    CONSTRAINT uq_traditional_exam_subject UNIQUE (exam_id, subject_id)
);

CREATE TABLE traditional_marks (
    id                   uuid PRIMARY KEY,
    exam_id              uuid NOT NULL REFERENCES traditional_exams (id) ON DELETE CASCADE,
    student_id           uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    subject_id           varchar(64) NOT NULL,
    raw_score            integer NOT NULL DEFAULT 0,
    percentage           double precision,
    grade_band           varchar(8),
    component_scores     text,
    confirmed_by_teacher boolean NOT NULL DEFAULT FALSE,
    confirmed_at         timestamp with time zone,
    entered_by           uuid,
    entered_at           timestamp with time zone NOT NULL DEFAULT now(),
    created_at           timestamp with time zone NOT NULL DEFAULT now(),
    updated_at           timestamp with time zone NOT NULL DEFAULT now(),
    version              bigint NOT NULL DEFAULT 0,
    CONSTRAINT uq_traditional_mark UNIQUE (exam_id, student_id, subject_id)
);

CREATE INDEX ix_traditional_marks_student ON traditional_marks (student_id, exam_id);
CREATE INDEX ix_traditional_marks_subject ON traditional_marks (exam_id, subject_id);

-- Coordinator-configured subject catalogue per grade (reused across exams).
CREATE TABLE traditional_subject_configs (
    id           uuid PRIMARY KEY,
    school_id    uuid,
    grade_level  varchar(64) NOT NULL,
    subject_id   varchar(64) NOT NULL,
    name         varchar(128) NOT NULL,
    max_score    integer NOT NULL,
    subject_type varchar(16) NOT NULL DEFAULT 'SINGLE',
    is_optional  boolean NOT NULL DEFAULT FALSE,
    components   text,
    order_index  integer NOT NULL DEFAULT 0
);

CREATE INDEX ix_traditional_subject_configs_scope ON traditional_subject_configs (school_id, grade_level);

-- Coordinator-configured grading bands per grade.
CREATE TABLE traditional_grading_configs (
    id            uuid PRIMARY KEY,
    school_id     uuid,
    grade_level   varchar(64) NOT NULL,
    bands         text NOT NULL,
    overall_bands text
);

CREATE INDEX ix_traditional_grading_configs_scope ON traditional_grading_configs (school_id, grade_level);

-- Per-teacher mark confirmation for an exam/grade.
CREATE TABLE traditional_confirmations (
    id           uuid PRIMARY KEY,
    exam_id      uuid NOT NULL REFERENCES traditional_exams (id) ON DELETE CASCADE,
    teacher_id   uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    grade_level  varchar(64) NOT NULL,
    confirmed_at timestamp with time zone
);

CREATE UNIQUE INDEX uq_traditional_confirmation ON traditional_confirmations (exam_id, teacher_id, grade_level);

CREATE TABLE traditional_edit_requests (
    id                  uuid PRIMARY KEY,
    exam_id             uuid NOT NULL REFERENCES traditional_exams (id) ON DELETE CASCADE,
    requester_id        uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    student_id          uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    subject_id          varchar(64) NOT NULL,
    old_score           integer NOT NULL,
    new_score           integer NOT NULL,
    reason              text NOT NULL,
    status              varchar(16) NOT NULL DEFAULT 'PENDING',
    coordinator_comment text,
    reviewed_by         uuid,
    reviewed_at         timestamp with time zone,
    created_at          timestamp with time zone NOT NULL DEFAULT now(),
    updated_at          timestamp with time zone NOT NULL DEFAULT now(),
    version             bigint NOT NULL DEFAULT 0,
    CONSTRAINT ck_traditional_edit_status CHECK (status IN ('PENDING','APPROVED','DENIED'))
);

CREATE INDEX ix_traditional_edit_requests_exam ON traditional_edit_requests (exam_id, status);

CREATE TABLE traditional_edit_permissions (
    id           uuid PRIMARY KEY,
    exam_id      uuid NOT NULL REFERENCES traditional_exams (id) ON DELETE CASCADE,
    student_id   uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    teacher_id   uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    granted_by   uuid NOT NULL,
    granted_at   timestamp with time zone NOT NULL DEFAULT now(),
    expires_at   timestamp with time zone NOT NULL,
    used         boolean NOT NULL DEFAULT FALSE
);

CREATE INDEX ix_traditional_edit_permissions_lookup ON traditional_edit_permissions (exam_id, student_id, teacher_id);
