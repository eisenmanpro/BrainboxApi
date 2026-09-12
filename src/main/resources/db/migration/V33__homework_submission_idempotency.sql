-- ============================================================
-- BrainboxApi V33: homework submission idempotency key
-- docs/ongoing/api_homework_changes.md: the client replays offline submits with a
-- stable clientSubmissionId; storing it lets a repeat be a no-op.
-- ============================================================

ALTER TABLE homework_submissions ADD COLUMN client_submission_id varchar(80);
