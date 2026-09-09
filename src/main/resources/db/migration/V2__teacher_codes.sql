-- ============================================================
-- BrainboxApi V2: teacher codes (CTC)
-- Contract: docs/backend_contracts/01_AUTH_IDENTITY_AND_ACCESS.md §7
-- Codes are server-issued only; a student may join a teacher via the
-- code (referredByTeacherCode -> joinedTeacherId + school scope).
-- ============================================================

CREATE TABLE teacher_codes (
    id              uuid PRIMARY KEY,
    code            varchar(8) NOT NULL,
    teacher_user_id uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    school_id       uuid REFERENCES schools (id),
    active          boolean NOT NULL DEFAULT TRUE,
    created_at      timestamp with time zone NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uq_teacher_code ON teacher_codes (code);
CREATE INDEX ix_teacher_codes_teacher ON teacher_codes (teacher_user_id);
