-- N3: server-driven school configuration and the teacher change-request gate.
--
-- The owner brief (docs/NEW/findings2.md, item 3) requires three things:
--   1. teacher settings drop-downs come from the school config the ICT admin
--      populates (classes) and from a BrainBox-managed subject catalogue;
--   2. a teacher's class/school/subjects edit is a REQUEST that the destination
--      school's admin approves or rejects, never a silent local write;
--   3. the approval gate carries enough provenance for the admin to see whether
--      the teacher is moving within the school or arriving from another school.
--
-- Existing installs upgrade in place: every column/table below is additive.

-- ICT-admin-populated class catalogue for one school: a JSON array of
-- {name, gradeLevel, stream?}. Teacher settings drop-downs read this list.
ALTER TABLE school_configs ADD COLUMN IF NOT EXISTS classes text;

-- BrainBox-managed subject catalogue. Clients never carry a hardcoded subject
-- list: GET /curriculum/subjects serves this table, and platform staff add rows
-- server-side. Seeded with the same KICD learning areas the paper-exam engine
-- already uses (traditional/TraditionalCurriculum.kt) so the two cannot disagree.
CREATE TABLE IF NOT EXISTS subjects (
    id          uuid PRIMARY KEY,
    code        varchar(32) NOT NULL,
    name        varchar(160) NOT NULL,
    grade_bands text,
    is_active   boolean NOT NULL DEFAULT TRUE,
    sort_order  integer NOT NULL DEFAULT 0,
    created_at  timestamp with time zone NOT NULL DEFAULT now(),
    updated_at  timestamp with time zone NOT NULL DEFAULT now(),
    version     bigint NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_subject_code ON subjects (code);
CREATE UNIQUE INDEX IF NOT EXISTS uq_subject_name ON subjects (name);

INSERT INTO subjects (id, code, name, grade_bands, sort_order) VALUES
    ('33333333-3333-4333-8333-000000000001', 'ENG',     'English',                        '["1-9"]', 1),
    ('33333333-3333-4333-8333-000000000002', 'KIS',     'Kiswahili',                      '["1-9"]', 2),
    ('33333333-3333-4333-8333-000000000003', 'MAT',     'Mathematics',                    '["1-9"]', 3),
    ('33333333-3333-4333-8333-000000000004', 'ENV',     'Environmental Activities',       '["1-3"]', 4),
    ('33333333-3333-4333-8333-000000000005', 'HYG',     'Hygiene and Nutrition',          '["1-3"]', 5),
    ('33333333-3333-4333-8333-000000000006', 'RE',      'Religious Education',            '["1-9"]', 6),
    ('33333333-3333-4333-8333-000000000007', 'CAT',     'Creative Arts',                  '["1-6"]', 7),
    ('33333333-3333-4333-8333-000000000008', 'PHE',     'Physical and Health Education',  '["1-9"]', 8),
    ('33333333-3333-4333-8333-000000000009', 'SCI',     'Science and Technology',         '["4-6"]', 9),
    ('33333333-3333-4333-8333-000000000010', 'SST',     'Social Studies',                 '["4-9"]', 10),
    ('33333333-3333-4333-8333-000000000011', 'HSC',     'Home Science',                   '["4-6"]', 11),
    ('33333333-3333-4333-8333-000000000012', 'AGR',     'Agriculture',                    '["4-9"]', 12),
    ('33333333-3333-4333-8333-000000000013', 'INTS',    'Integrated Science',             '["7-9"]', 13),
    ('33333333-3333-4333-8333-000000000014', 'BUS',     'Business Studies',               '["7-9"]', 14),
    ('33333333-3333-4333-8333-000000000015', 'PRETECH', 'Pre-Technical Studies',          '["7-9"]', 15),
    ('33333333-3333-4333-8333-000000000016', 'CAS',     'Creative Arts and Sports',       '["7-9"]', 16);

-- Approved class/subject assignments live on the teacher's settings document so
-- a profile read does not depend on a teacher owning a matching class. Profile
-- reads prefer these and fall back to the derived class data.
ALTER TABLE teacher_settings ADD COLUMN IF NOT EXISTS primary_class_name varchar(255);
ALTER TABLE teacher_settings ADD COLUMN IF NOT EXISTS grades_taught text;

-- One row per teacher change request. school_id is the DECIDING school (the
-- destination school for a transfer); home_school_id is where the teacher is
-- today, so the approval gate can state whether the request comes from within
-- the school or from another school.
CREATE TABLE IF NOT EXISTS teacher_change_requests (
    id             uuid PRIMARY KEY,
    teacher_id     uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    kind           varchar(32) NOT NULL,
    school_id      uuid NOT NULL REFERENCES schools (id) ON DELETE CASCADE,
    home_school_id uuid REFERENCES schools (id) ON DELETE SET NULL,
    current_value  text,
    proposed_value text NOT NULL,
    status         varchar(16) NOT NULL DEFAULT 'PENDING',
    reviewer_id    uuid REFERENCES users (id) ON DELETE SET NULL,
    review_note    text,
    reviewed_at    timestamp with time zone,
    created_at     timestamp with time zone NOT NULL DEFAULT now(),
    updated_at     timestamp with time zone NOT NULL DEFAULT now(),
    version        bigint NOT NULL DEFAULT 0,
    CONSTRAINT ck_tcr_kind CHECK (kind IN ('CLASS_ASSIGNMENT', 'SCHOOL', 'SUBJECTS')),
    CONSTRAINT ck_tcr_status CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED'))
);
CREATE INDEX IF NOT EXISTS ix_tcr_school_status ON teacher_change_requests (school_id, status);
CREATE INDEX IF NOT EXISTS ix_tcr_home_status ON teacher_change_requests (home_school_id, status);
CREATE INDEX IF NOT EXISTS ix_tcr_teacher_status ON teacher_change_requests (teacher_id, status);
