-- FEAT-01: the public school list is the schools directory's editorial surface.
-- The detail payload already carried a gallery; the list carried only the legacy
-- logoUrls, so no client could render the featured rail or multi-image card the
-- web was designed around. These are additive, nullable/defaulted columns and the
-- list payload fills them from here.
--
-- is_featured is moderator-only (FEAT-06): a school admin must never be able to
-- promote its own listing. The platform-moderator write path is the only one that
-- sets it, and it is audited.
ALTER TABLE schools ADD COLUMN IF NOT EXISTS image_urls text;
ALTER TABLE schools ADD COLUMN IF NOT EXISTS is_featured boolean NOT NULL DEFAULT false;
ALTER TABLE schools ADD COLUMN IF NOT EXISTS featured_tagline varchar(255);
ALTER TABLE schools ADD COLUMN IF NOT EXISTS story text;

-- Featured-first ordering is a cheap read; keep the index narrow.
CREATE INDEX IF NOT EXISTS ix_schools_is_featured ON schools (is_featured);
