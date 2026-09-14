-- ============================================================
-- BrainboxApi V65: confidence auto-approval on moderation outcomes
-- Phase 7.4b. A machine decision must never be mistakable for a human one, so the
-- outcome records auto_approved plus the validator confidence score and the
-- resolving reviewer (null for an auto-approval). content_feedback gains provider so
-- the preference dataset keeps the full generation identity.
--
-- H2 (PostgreSQL mode) and PostgreSQL compatible. confidence_score is double
-- precision rather than numeric(5,4) so it maps to the entity Double exactly and
-- passes ddl-auto: validate on both engines.
-- ============================================================

ALTER TABLE moderation_outcomes ADD COLUMN reviewer_id uuid REFERENCES users (id) ON DELETE SET NULL;
ALTER TABLE moderation_outcomes ADD COLUMN auto_approved boolean NOT NULL DEFAULT false;
ALTER TABLE moderation_outcomes ADD COLUMN confidence_score double precision;
ALTER TABLE content_feedback ADD COLUMN provider varchar(32);
