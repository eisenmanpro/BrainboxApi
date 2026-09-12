-- ============================================================
-- BrainboxApi V41: teacher live-class hosting surface
-- docs/ongoing/api_live_class_changes.md: teacher authoring settings, the host
-- roster, and the replay-safe chat transcript. Every teacher write carries a
-- client id so the LiveSyncWorker outbox can replay it idempotently.
-- ============================================================

ALTER TABLE live_classes ADD COLUMN client_id varchar(80);
ALTER TABLE live_classes ADD COLUMN visibility varchar(32) NOT NULL DEFAULT 'CLASS_ONLY';
ALTER TABLE live_classes ADD COLUMN auto_record boolean NOT NULL DEFAULT TRUE;
ALTER TABLE live_classes ADD COLUMN mute_on_join boolean NOT NULL DEFAULT TRUE;
ALTER TABLE live_classes ADD COLUMN waiting_room boolean NOT NULL DEFAULT FALSE;
ALTER TABLE live_classes ADD COLUMN allow_chat boolean NOT NULL DEFAULT TRUE;
ALTER TABLE live_classes ADD COLUMN allow_q_and_a boolean NOT NULL DEFAULT TRUE;
ALTER TABLE live_classes ADD COLUMN participant_ids text;
ALTER TABLE live_classes ADD COLUMN material_ids text;
ALTER TABLE live_classes ADD COLUMN analytics_id varchar(80);

CREATE UNIQUE INDEX uq_live_class_client ON live_classes (client_id);

CREATE TABLE live_class_participants (
    id         uuid PRIMARY KEY,
    class_id   uuid NOT NULL REFERENCES live_classes (id) ON DELETE CASCADE,
    user_id    uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    user_name  varchar(160) NOT NULL,
    role       varchar(16) NOT NULL DEFAULT 'STUDENT'
               CHECK (role IN ('STUDENT','TEACHER','INSTRUCTOR','SUPPORT','COHOST')),
    is_muted   boolean NOT NULL DEFAULT FALSE,
    join_time  timestamp with time zone NOT NULL,
    is_removed boolean NOT NULL DEFAULT FALSE
);

CREATE UNIQUE INDEX uq_live_participant ON live_class_participants (class_id, user_id);

CREATE TABLE live_class_messages (
    id        uuid PRIMARY KEY,
    client_id varchar(80) NOT NULL,
    class_id  uuid NOT NULL REFERENCES live_classes (id) ON DELETE CASCADE,
    user_id   uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    user_name varchar(160) NOT NULL,
    user_role varchar(16) NOT NULL DEFAULT 'STUDENT',
    message   text NOT NULL,
    sent_at   timestamp with time zone NOT NULL,
    is_pinned boolean NOT NULL DEFAULT FALSE
);

CREATE UNIQUE INDEX uq_live_message_client ON live_class_messages (client_id);
CREATE INDEX ix_live_messages_class ON live_class_messages (class_id, sent_at);
