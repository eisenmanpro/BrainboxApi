-- ============================================================
-- BrainboxApi V67: Tier 0 curriculum skeleton (Phase 7.5b-1)
-- Extends the CBC strand table into a grade -> subject -> strand ->
-- sub-strand -> topic skeleton, versioned so content can be tagged against a
-- named curriculum version. The catalogue itself is authored BrainBox data in
-- resources/curriculum/ke-cbc-v1.json, seeded deterministically and
-- idempotently by CurriculumSeeder; KICD documents are not a source.
--
-- H2 (PostgreSQL mode) and PostgreSQL compatible: snake_case, app-assigned
-- UUID primary keys, created_at/updated_at/version on new tables, no partial
-- indexes and no now()+interval arithmetic.
-- ============================================================

CREATE TABLE curriculum_versions (
    id             uuid PRIMARY KEY,
    country_code   varchar(8) NOT NULL,
    curriculum     varchar(32) NOT NULL,
    curriculum_version varchar(32) NOT NULL,
    name           varchar(200) NOT NULL,
    notes          text,
    is_active      boolean NOT NULL DEFAULT false,
    created_at     timestamp with time zone NOT NULL DEFAULT now(),
    updated_at     timestamp with time zone NOT NULL DEFAULT now(),
    version        bigint NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX uq_curriculum_versions ON curriculum_versions (country_code, curriculum, curriculum_version);

ALTER TABLE cbc_strands ADD COLUMN parent_id uuid REFERENCES cbc_strands (id) ON DELETE CASCADE;
ALTER TABLE cbc_strands ADD COLUMN level varchar(16) NOT NULL DEFAULT 'STRAND' CHECK (level IN ('STRAND','SUBSTRAND'));
ALTER TABLE cbc_strands ADD COLUMN curriculum_version varchar(32);

-- Codes become the stable seeding key; the legacy (code, grade_level) index is
-- kept for the existing CBC analytics lookups.
CREATE UNIQUE INDEX uq_cbc_strands_code ON cbc_strands (code);
CREATE INDEX ix_cbc_strands_parent ON cbc_strands (parent_id, sort_order);

ALTER TABLE curriculum_map ADD COLUMN curriculum_version varchar(32);
