-- Phase 7.5 supervisor task loop: persist the bounded generate/validate/revise
-- state on the durable job so a crash resumes the loop instead of restarting it.
ALTER TABLE generation_jobs ADD COLUMN loop_iterations integer NOT NULL DEFAULT 0;
ALTER TABLE generation_jobs ADD COLUMN loop_cost_micros bigint NOT NULL DEFAULT 0;
ALTER TABLE generation_jobs ADD COLUMN loop_feedback text;
