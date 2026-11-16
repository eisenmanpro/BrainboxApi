-- RO-09: a roster-only provisioning write may be queued offline by the Android
-- roster screen. The server mints the learner id, so replay needs a stable
-- client-generated id to match instead of creating a duplicate. The id is
-- optional (online-first clients can omit it) and unique when present.
ALTER TABLE users ADD COLUMN IF NOT EXISTS provisioning_client_id varchar(64);

-- PostgreSQL treats NULLs as distinct in a unique index, so rows without a
-- client id never collide; no partial index is needed (H2, used by the test
-- profile, also accepts this form).
CREATE UNIQUE INDEX IF NOT EXISTS ux_users_provisioning_client_id
    ON users (provisioning_client_id);
