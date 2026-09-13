-- ============================================================
-- BrainboxApi V49: account verification state + CTC freeze
-- docs/ongoing/api_teacher_roster_changes.md (teacher auth lifecycle).
-- verification_status carries the client AccountStatus state machine
-- (PENDING_VERIFICATION | VERIFIED | REJECTED | FROZEN); teacher_codes.frozen
-- lets a teacher freeze their Class Teacher Code so students cannot join.
-- ============================================================

ALTER TABLE users ADD COLUMN verification_status varchar(24) NOT NULL DEFAULT 'VERIFIED';
UPDATE users SET verification_status = 'PENDING_VERIFICATION' WHERE is_verified = false;
ALTER TABLE teacher_codes ADD COLUMN frozen boolean NOT NULL DEFAULT false;
