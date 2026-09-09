-- ============================================================
-- BrainboxApi V3: admin identity support
-- Contract: docs/backend_contracts/01_AUTH_IDENTITY_AND_ACCESS.md
-- §2.2 (admin user mgmt), §7.2 (teachers + CTC), §9 (schools).
-- ============================================================

ALTER TABLE schools ADD COLUMN county varchar(128);
ALTER TABLE schools ADD COLUMN location varchar(255);
ALTER TABLE schools ADD COLUMN logo_url varchar(512);

-- Teacher subject/profile record; the teacher account itself is a users row
-- and the CTC code lives in teacher_codes (server-issued only).
CREATE TABLE teacher_profiles (
    id         uuid PRIMARY KEY,
    user_id    uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    school_id  uuid REFERENCES schools (id),
    subject    varchar(128),
    created_at timestamp with time zone NOT NULL DEFAULT now(),
    updated_at timestamp with time zone NOT NULL DEFAULT now(),
    version    bigint NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX uq_teacher_profile_user ON teacher_profiles (user_id);
CREATE INDEX ix_teacher_profiles_school ON teacher_profiles (school_id);
