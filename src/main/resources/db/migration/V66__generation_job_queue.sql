-- ============================================================
-- BrainboxApi V66: durable generation job queue
-- Phase 7.5a. The router already upserts generation_jobs; this adds the durable
-- request payload and the retry schedule so any JVM (api|worker|both) can claim,
-- retry and reclaim work without sharing request threads.
--
-- H2 (PostgreSQL mode) and PostgreSQL compatible: plain ALTERs, snake_case, no
-- partial indexes and no now()+interval arithmetic. next_attempt_at is nullable:
-- null means immediately claimable.
-- ============================================================

ALTER TABLE generation_jobs ADD COLUMN request_payload text;
ALTER TABLE generation_jobs ADD COLUMN max_attempts integer NOT NULL DEFAULT 5;
ALTER TABLE generation_jobs ADD COLUMN next_attempt_at timestamp with time zone;
