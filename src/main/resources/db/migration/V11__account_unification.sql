-- F9/N6: one account (the learner account is the guardian account) and the learner PIN.
--
-- credential_kind records which shape an account's credential is: PIN (4 digits, learners)
-- or PASSWORD (staff). Login never validates the shape (legacy learner passwords must keep
-- working); change/reset enforce the stored kind, and a signup that names PIN enforces the
-- 4-digit rule. Phone verification is a separate, infrastructure-blocked slice and is
-- deliberately NOT half-added here (no dead column).
ALTER TABLE users ADD COLUMN IF NOT EXISTS credential_kind varchar(16) NOT NULL DEFAULT 'PASSWORD';
