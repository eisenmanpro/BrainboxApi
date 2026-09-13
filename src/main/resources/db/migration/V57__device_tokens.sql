-- ============================================================
-- BrainboxApi V57: FCM device tokens (LC-1)
-- docs/ongoing/api_push_changes.md. One row per registration token; a token
-- may move between accounts, so the latest registration wins (unique token).
-- ============================================================

CREATE TABLE device_tokens (
    id          uuid PRIMARY KEY,
    user_id     uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    token       varchar(512) NOT NULL,
    platform    varchar(32) NOT NULL DEFAULT 'ANDROID',
    app_version varchar(32),
    created_at  timestamp with time zone NOT NULL DEFAULT now(),
    updated_at  timestamp with time zone NOT NULL DEFAULT now(),
    version     bigint NOT NULL DEFAULT 0,
    CONSTRAINT uq_device_token UNIQUE (token)
);

CREATE INDEX ix_device_tokens_user ON device_tokens (user_id);
