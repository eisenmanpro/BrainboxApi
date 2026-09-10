-- ============================================================
-- BrainboxApi V23: notifications + news
-- Contract: docs/backend_contracts/05_... §5/§6 + the Android AppNotificationApi/NewsApi.
-- System notifications (subscription lifecycle reminders) are materialized on read
-- and deduplicated by (user_id, dedupe_key).
-- ============================================================

CREATE TABLE notifications (
    id           uuid PRIMARY KEY,
    user_id      uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    title        varchar(220) NOT NULL,
    message      text NOT NULL,
    type         varchar(24) NOT NULL,
    urgency      varchar(16) NOT NULL DEFAULT 'NORMAL',
    priority     varchar(16) NOT NULL DEFAULT 'NORMAL',
    action_route varchar(64),
    action_label varchar(64),
    metadata     text,
    is_read      boolean NOT NULL DEFAULT FALSE,
    read_at      timestamp with time zone,
    is_archived  boolean NOT NULL DEFAULT FALSE,
    dedupe_key   varchar(200),
    created_at   timestamp with time zone NOT NULL DEFAULT now(),
    updated_at   timestamp with time zone NOT NULL DEFAULT now(),
    version      bigint NOT NULL DEFAULT 0
);

CREATE INDEX ix_notifications_user ON notifications (user_id, is_archived, created_at);
CREATE UNIQUE INDEX uq_notifications_dedupe ON notifications (user_id, dedupe_key);

CREATE TABLE news_items (
    id           uuid PRIMARY KEY,
    title        varchar(240) NOT NULL,
    content      text NOT NULL DEFAULT '',
    image_url    varchar(512),
    category     varchar(64) NOT NULL DEFAULT 'General',
    author_id    uuid REFERENCES users (id) ON DELETE SET NULL,
    published_at timestamp with time zone,
    status       varchar(16) NOT NULL DEFAULT 'PUBLISHED' CHECK (status IN ('DRAFT','PUBLISHED')),
    tags         text,
    likes        integer NOT NULL DEFAULT 0,
    dislikes     integer NOT NULL DEFAULT 0,
    comment_count integer NOT NULL DEFAULT 0,
    created_at   timestamp with time zone NOT NULL DEFAULT now(),
    updated_at   timestamp with time zone NOT NULL DEFAULT now(),
    version      bigint NOT NULL DEFAULT 0
);

CREATE INDEX ix_news_status_published ON news_items (status, published_at);
