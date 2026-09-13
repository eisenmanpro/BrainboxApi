-- ============================================================
-- BrainboxApi V54: password recovery (docs/ongoing/api_auth_changes.md)
-- One row per reset challenge: a hashed one-time code, then a short-lived
-- single-use reset token. Delivery is out of band (SMS/email); the OTP is never
-- returned by the API.
-- ============================================================

CREATE TABLE password_reset_challenges (
    id                      uuid PRIMARY KEY,
    identifier              varchar(160) NOT NULL,
    user_id                 uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    otp_hash                varchar(64) NOT NULL,
    expires_at              timestamp with time zone NOT NULL,
    attempts                integer NOT NULL DEFAULT 0,
    verified                boolean NOT NULL DEFAULT false,
    reset_token_hash        varchar(64),
    reset_token_expires_at  timestamp with time zone,
    consumed                boolean NOT NULL DEFAULT false,
    created_at              timestamp with time zone NOT NULL DEFAULT now(),
    updated_at              timestamp with time zone NOT NULL DEFAULT now(),
    version                 bigint NOT NULL DEFAULT 0
);

CREATE INDEX ix_password_reset_identifier ON password_reset_challenges (identifier, consumed, created_at);
CREATE INDEX ix_password_reset_token ON password_reset_challenges (reset_token_hash);
