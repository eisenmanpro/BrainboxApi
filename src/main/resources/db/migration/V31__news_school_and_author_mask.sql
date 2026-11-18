-- Audit 3: school-scoped news filtering and an opt-out public byline.
ALTER TABLE news_items ADD COLUMN school_id uuid;
ALTER TABLE news_items ADD COLUMN author_masked boolean NOT NULL DEFAULT false;

CREATE INDEX idx_news_items_school_id ON news_items (school_id);
