-- ============================================================
-- BrainboxApi V39: content post lifecycle (schedule + archive)
-- docs/ongoing/api_content_changes.md: a post carries PUBLISHED | SCHEDULED |
-- ARCHIVED and a scheduled post is held until its publish instant.
-- ============================================================

ALTER TABLE learning_posts ADD COLUMN status varchar(16) NOT NULL DEFAULT 'PUBLISHED';
ALTER TABLE learning_posts ADD COLUMN publish_at timestamp with time zone;
CREATE INDEX ix_learning_posts_status ON learning_posts (status, publish_at);
