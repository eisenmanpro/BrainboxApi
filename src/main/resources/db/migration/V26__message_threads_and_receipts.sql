-- Message conversations + delivery receipts (findings2 "NEW Message compose" 3/4).
--
-- A reply must join the conversation it answers instead of becoming a new thread, and the
-- sent copy needs a delivery/read state that follows the recipient's inbox copy. The
-- msg_group already identifies one send (and its fan-out copies); thread_root_group now
-- identifies the whole conversation across replies, and parent_message_id records the
-- message a reply answers.
ALTER TABLE messages ADD COLUMN IF NOT EXISTS thread_root_group VARCHAR(128);
ALTER TABLE messages ADD COLUMN IF NOT EXISTS parent_message_id UUID;

CREATE INDEX IF NOT EXISTS ix_messages_msg_group ON messages (msg_group);
CREATE INDEX IF NOT EXISTS ix_messages_thread_root ON messages (thread_root_group);
