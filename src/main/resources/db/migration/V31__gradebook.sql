-- ============================================================
-- BrainboxApi V31: teacher gradebook (assessments + manual grades)
-- Contract: BrainBox/docs/ongoing/api_gradebook_changes.md
-- ============================================================

CREATE TABLE gradebook_assessments (
    id                     uuid PRIMARY KEY,
    client_id              varchar(80) NOT NULL,
    class_id               uuid NOT NULL REFERENCES teacher_classes (id) ON DELETE CASCADE,
    title                  varchar(200) NOT NULL,
    assessment_type        varchar(16) NOT NULL,
    max_score              integer NOT NULL,
    date_assigned          timestamp with time zone NOT NULL,
    cbc_strand_tag         varchar(128),
    term                   varchar(16) NOT NULL,
    is_published           boolean NOT NULL DEFAULT FALSE,
    counts_toward_average  boolean NOT NULL DEFAULT TRUE,
    created_by             uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    school_id              uuid,
    created_at             timestamp with time zone NOT NULL DEFAULT now(),
    updated_at             timestamp with time zone NOT NULL DEFAULT now(),
    version                bigint NOT NULL DEFAULT 0,
    CONSTRAINT ck_gradebook_assessment_type CHECK (assessment_type IN ('HOMEWORK','EXAM','QUIZ','CONTEST')),
    CONSTRAINT ck_gradebook_assessment_term CHECK (term IN ('TERM_1','TERM_2','TERM_3'))
);

CREATE UNIQUE INDEX uq_gradebook_assessment_client ON gradebook_assessments (client_id);
CREATE INDEX ix_gradebook_assessments_class ON gradebook_assessments (class_id, term);

CREATE TABLE gradebook_entries (
    id                uuid PRIMARY KEY,
    client_id         varchar(80) NOT NULL,
    class_id          uuid NOT NULL REFERENCES teacher_classes (id) ON DELETE CASCADE,
    assessment_id     varchar(80) NOT NULL,
    teacher_id        uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    student_id        uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    student_name      varchar(160) NOT NULL,
    assessment_type   varchar(16) NOT NULL,
    assessment_title  varchar(200),
    raw_score         integer NOT NULL,
    max_score         integer NOT NULL,
    percentage        integer NOT NULL,
    cbc_strand_tag    varchar(128),
    teacher_note      text,
    graded_at         timestamp with time zone NOT NULL,
    created_at        timestamp with time zone NOT NULL DEFAULT now(),
    updated_at        timestamp with time zone NOT NULL DEFAULT now(),
    version           bigint NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX uq_gradebook_entry_client ON gradebook_entries (client_id);
CREATE UNIQUE INDEX uq_gradebook_entry ON gradebook_entries (class_id, assessment_id, student_id);
CREATE INDEX ix_gradebook_entries_class ON gradebook_entries (class_id);
CREATE INDEX ix_gradebook_entries_student ON gradebook_entries (student_id);
