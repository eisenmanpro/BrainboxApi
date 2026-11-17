-- Per-copy message state. Every message has one SENT row (sender) and one INBOX row per
-- recipient, so these columns are naturally scoped to the account that owns the copy.
ALTER TABLE messages ADD COLUMN is_archived boolean NOT NULL DEFAULT false;
ALTER TABLE messages ADD COLUMN is_flagged boolean NOT NULL DEFAULT false;

CREATE INDEX ix_messages_recipient_archived ON messages (recipient_id, folder, is_archived);
CREATE INDEX ix_messages_sender_archived ON messages (sender_id, folder, is_archived);
