-- ============================================================
-- BrainboxApi V71: per-school attribution for generation jobs (H3)
-- Phase H3 daily generation budgets. Every generation_jobs row records the
-- requesting user's school when known so a per-school daily budget can be
-- counted. Platform batch/proactive work leaves it null and counts only against
-- the platform-wide budget. ON DELETE SET NULL keeps the job rows when a school
-- is deleted; the composite index supports the (school_id, created_at) day count.
--
-- H2 (PostgreSQL mode) and PostgreSQL compatible: plain ALTER, snake_case, a
-- nullable uuid FK and a plain composite index.
-- ============================================================

ALTER TABLE generation_jobs ADD COLUMN school_id uuid REFERENCES schools (id) ON DELETE SET NULL;
CREATE INDEX ix_generation_jobs_school_created ON generation_jobs (school_id, created_at);
