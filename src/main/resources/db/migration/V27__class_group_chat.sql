-- ============================================================
-- BrainboxApi V27: teacher class-group chat (doc 04 §12)
-- REST surface; realtime WebSocket transport arrives with Phase 6.
-- ============================================================

CREATE TABLE class_groups (
    id                   uuid PRIMARY KEY,
    class_id             uuid NOT NULL REFERENCES teacher_classes (id) ON DELETE CASCADE,
    teacher_id           uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    teacher_name         varchar(160) NOT NULL,
    name                 varchar(200) NOT NULL,
    description          varchar(500),
    is_announcement_mode boolean NOT NULL DEFAULT FALSE,
    teacher_last_read_at timestamp with time zone,
    teacher_muted_until  timestamp with time zone,
    created_at           timestamp with time zone NOT NULL DEFAULT now(),
    updated_at           timestamp with time zone NOT NULL DEFAULT now(),
    version              bigint NOT NULL DEFAULT 0
);

CREATE INDEX ix_class_groups_teacher ON class_groups (teacher_id, updated_at);

CREATE TABLE class_group_members (
    id          uuid PRIMARY KEY,
    group_id    uuid NOT NULL REFERENCES class_groups (id) ON DELETE CASCADE,
    member_id   uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    member_name varchar(160) NOT NULL,
    member_role varchar(16) NOT NULL,
    muted_until timestamp with time zone,
    joined_at   timestamp with time zone NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uq_class_group_member ON class_group_members (group_id, member_id);

CREATE TABLE class_group_messages (
    id              uuid PRIMARY KEY,
    group_id        uuid NOT NULL REFERENCES class_groups (id) ON DELETE CASCADE,
    sender_id       uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    sender_name     varchar(160) NOT NULL,
    sender_role     varchar(16) NOT NULL,
    text            text NOT NULL,
    is_pinned       boolean NOT NULL DEFAULT FALSE,
    is_announcement boolean NOT NULL DEFAULT FALSE,
    reply_to_id     uuid,
    attachments     text,
    created_at      timestamp with time zone NOT NULL DEFAULT now(),
    updated_at      timestamp with time zone NOT NULL DEFAULT now(),
    version         bigint NOT NULL DEFAULT 0
);

CREATE INDEX ix_class_group_messages ON class_group_messages (group_id, created_at);

CREATE TABLE class_group_polls (
    id         uuid PRIMARY KEY,
    group_id   uuid NOT NULL REFERENCES class_groups (id) ON DELETE CASCADE,
    created_by uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    question   varchar(500) NOT NULL,
    options    text NOT NULL,
    is_active  boolean NOT NULL DEFAULT TRUE,
    created_at timestamp with time zone NOT NULL DEFAULT now(),
    updated_at timestamp with time zone NOT NULL DEFAULT now(),
    version    bigint NOT NULL DEFAULT 0
);

CREATE TABLE class_group_poll_votes (
    id           uuid PRIMARY KEY,
    poll_id      uuid NOT NULL REFERENCES class_group_polls (id) ON DELETE CASCADE,
    user_id      uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    option_index integer NOT NULL,
    voted_at     timestamp with time zone NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uq_class_group_poll_vote ON class_group_poll_votes (poll_id, user_id);
