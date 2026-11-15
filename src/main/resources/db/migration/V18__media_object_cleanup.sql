-- D16/A23 follow-up: proxied branding uploads are stored before the admin saves
-- the config, so an abandoned upload used to leak an unreferenced object forever.
-- Every branding upload is registered here; the config PUT claims the URL it
-- keeps, and the media scheduler deletes an unclaimed object after the grace
-- period. Superseded branding objects are deleted immediately by the same
-- config write, so replacing a logo/stamp never leaves the old bytes behind.
CREATE TABLE IF NOT EXISTS media_objects (
    storage_key   varchar(120) PRIMARY KEY,
    purpose       varchar(32) NOT NULL,
    created_at    timestamp with time zone NOT NULL DEFAULT now(),
    referenced_at timestamp with time zone
);
CREATE INDEX IF NOT EXISTS idx_media_objects_unreferenced
    ON media_objects (referenced_at, created_at);
