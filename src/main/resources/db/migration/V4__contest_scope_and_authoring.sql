-- F4: contest authoring/visibility. Contests are grade-level resources:
-- a SCHOOL_GRADE contest is visible to every class in that school at that grade
-- (there is deliberately no per-class targeting until the owner asks for it).
ALTER TABLE contests ADD COLUMN scope VARCHAR(16) NOT NULL DEFAULT 'GLOBAL';
ALTER TABLE contests ADD COLUMN school_id UUID;
ALTER TABLE contests ADD COLUMN rules TEXT;
ALTER TABLE contests ADD COLUMN finalised_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE contests ADD COLUMN results_published_at TIMESTAMP WITH TIME ZONE;

-- Existing rows are BrainBox-team contests: global, already published.
UPDATE contests SET scope = 'GLOBAL' WHERE scope IS NULL;

CREATE INDEX IF NOT EXISTS idx_contests_school_scope ON contests (school_id, scope);
CREATE INDEX IF NOT EXISTS idx_contests_created_by ON contests (created_by);
