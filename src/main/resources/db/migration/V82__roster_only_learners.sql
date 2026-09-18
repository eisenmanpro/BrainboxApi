-- Roster-only learners: a class teacher (or a coordinator/ICT admin) can
-- provision a real student record for a pupil who has no smartphone, so the
-- traditional exam engine can enter marks, rank and print reports for them.
-- The account is login-disabled (account_kind = ROSTER_ONLY) and can later be
-- upgraded to FULL when the learner gets a phone.
ALTER TABLE users ADD COLUMN account_kind varchar(16) NOT NULL DEFAULT 'FULL';
ALTER TABLE users ADD COLUMN provisioned_by uuid;
ALTER TABLE users ADD COLUMN provisioned_at timestamp with time zone;
ALTER TABLE users ADD COLUMN guardian_name varchar(160);
ALTER TABLE users ADD COLUMN guardian_phone varchar(32);
CREATE INDEX ix_users_school_account_kind ON users (school_id, account_kind);
