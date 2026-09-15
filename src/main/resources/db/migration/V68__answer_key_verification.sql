-- ============================================================
-- BrainboxApi V68: independent answer-key verification (Phase 7.5f)
-- Auto-approval is the default bulk path, so a present-but-wrong answer key must
-- not ship. A second, separate model interaction solves each question without the
-- stored key; the unit records the agreement ratio, when it was verified and which
-- model verified it. The machine gate refuses an assessment unless every key
-- agrees, so an unverified or disagreeing unit stays UNREVIEWED in the exception
-- queue. answer_key_agreement is double precision to match the entity Double and
-- pass ddl-auto: validate on H2 (PostgreSQL mode) and PostgreSQL.
-- ============================================================

ALTER TABLE content_units ADD COLUMN answer_key_agreement double precision;
ALTER TABLE content_units ADD COLUMN answer_key_verified_at timestamp with time zone;
ALTER TABLE content_units ADD COLUMN answer_key_verified_model varchar(64);
