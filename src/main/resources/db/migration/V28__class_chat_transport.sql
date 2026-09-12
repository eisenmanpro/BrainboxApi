-- ============================================================
-- BrainboxApi V28: class-group chat transport (parent/student + idempotency)
-- Contract: BrainBox/docs/ongoing/api_class_group_chat_changes.md
-- ============================================================

-- Stable client id per message for offline replay de-duplication.
ALTER TABLE class_group_messages ADD COLUMN client_message_id varchar(128);
CREATE UNIQUE INDEX uq_class_group_message_client ON class_group_messages (group_id, client_message_id);

-- Per-caller read markers (teacher, parent and student all tracked uniformly).
CREATE TABLE class_group_reads (
    id           uuid PRIMARY KEY,
    group_id     uuid NOT NULL REFERENCES class_groups (id) ON DELETE CASCADE,
    user_id      uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    last_read_at timestamp with time zone NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uq_class_group_read ON class_group_reads (group_id, user_id);

-- Superseded by class_group_reads.
ALTER TABLE class_groups DROP COLUMN teacher_last_read_at;

-- Chat deep links (class_group_chat/{groupId}/{groupName}) exceed the original width.
ALTER TABLE notifications ALTER COLUMN action_route TYPE varchar(255);
