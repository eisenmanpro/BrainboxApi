-- ============================================================
-- BrainboxApi V70: generation job source classification (H2)
-- Phase H2. The worker previously drained every queued job identically, so an
-- operator could not keep serving user requests while pausing proactive/batch
-- material generation. Each job now records where it came from:
--   USER      - a teacher/student request (interactive)
--   BATCH     - the Tier 1 batch producer (notes/learning material ahead of demand)
--   PROACTIVE - reserved for the future autonomous agent
-- The claim index supports the worker's source filter and its USER-first
-- ordering; existing rows default to USER so behaviour is unchanged until a
-- client records a different source.
--
-- H2 (PostgreSQL mode) and PostgreSQL compatible: plain ALTER, snake_case, a
-- varchar CHECK and a plain composite index.
-- ============================================================

ALTER TABLE generation_jobs ADD COLUMN source varchar(16) NOT NULL DEFAULT 'USER' CHECK (source IN ('USER','BATCH','PROACTIVE'));
CREATE INDEX ix_generation_jobs_claim ON generation_jobs (status, source, created_at);
