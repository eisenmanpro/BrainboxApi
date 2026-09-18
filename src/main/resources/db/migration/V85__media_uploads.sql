-- Presigned client-direct uploads (Phase 6). The API signs a short-lived PUT
-- URL so the bytes go straight to S3/MinIO; one row per ticket lets confirm
-- verify the real format before the file is served, and lets the sweeper delete
-- an abandoned or rejected object.
CREATE TABLE media_uploads (
    id            uuid PRIMARY KEY,
    owner_id      uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    storage_key   varchar(120) NOT NULL,
    declared_kind varchar(16) NOT NULL,
    purpose       varchar(32) NOT NULL,
    status        varchar(16) NOT NULL DEFAULT 'PENDING',
    size_bytes    bigint,
    expires_at    timestamp with time zone NOT NULL,
    created_at    timestamp with time zone NOT NULL DEFAULT now(),
    updated_at    timestamp with time zone NOT NULL DEFAULT now(),
    version       bigint NOT NULL DEFAULT 0
);
CREATE INDEX ix_media_uploads_owner ON media_uploads (owner_id, created_at);
CREATE INDEX ix_media_uploads_sweep ON media_uploads (status, expires_at);
