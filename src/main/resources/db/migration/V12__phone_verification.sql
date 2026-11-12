-- Phone verification (docs/phone_verification.md): server-side OTP over an SMS gateway.
--
-- The provider only delivers the SMS; the API generates, hashes, expires and attempt-limits
-- the code. Additive only: existing accounts have phone_verified_at = NULL, which means
-- "not verified" and never blocks anything while the feature flag is off.
ALTER TABLE users ADD COLUMN IF NOT EXISTS phone_verified_at timestamp with time zone;

-- One row per requested code. A newer request supersedes (consumes) the older row, so at most
-- one challenge per account can be active. code_hash is SHA-256(challenge id + ":" + code);
-- the plain code is never stored.
CREATE TABLE IF NOT EXISTS phone_verification_challenges (
    id           uuid PRIMARY KEY,
    user_id      uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    phone_number varchar(32) NOT NULL,
    code_hash    varchar(64) NOT NULL,
    attempts     integer NOT NULL DEFAULT 0,
    sent_at      timestamp with time zone NOT NULL,
    expires_at   timestamp with time zone NOT NULL,
    consumed     boolean NOT NULL DEFAULT FALSE,
    consumed_at  timestamp with time zone,
    created_at   timestamp with time zone NOT NULL DEFAULT now(),
    updated_at   timestamp with time zone NOT NULL DEFAULT now(),
    version      bigint NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_pvc_user_active ON phone_verification_challenges (user_id, consumed);
CREATE INDEX IF NOT EXISTS idx_pvc_phone_sent ON phone_verification_challenges (phone_number, sent_at);
