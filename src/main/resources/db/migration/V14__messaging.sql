-- ============================================================
-- BrainboxApi V14: messaging
-- Contract: docs/backend_contracts/05_... §2
-- One row per delivery; folder 'inbox' for recipients, 'sent' for senders.
-- msg_group = client message id (msg_<ts>_<userId>) for idempotent replay.
-- ============================================================

CREATE TABLE messages (
    id                 uuid PRIMARY KEY,
    msg_group          varchar(128) NOT NULL,
    sender_id          uuid NOT NULL REFERENCES users (id),
    recipient_id       uuid REFERENCES users (id),
    subject            varchar(255),
    body               text NOT NULL,
    attachments        text,
    folder             varchar(8) NOT NULL DEFAULT 'inbox'
                       CHECK (folder IN ('inbox','sent','outbox')),
    is_read            boolean NOT NULL DEFAULT FALSE,
    read_at            timestamp with time zone,
    intended_for_parent boolean NOT NULL DEFAULT FALSE,
    created_at         timestamp with time zone NOT NULL DEFAULT now()
);

CREATE INDEX ix_messages_recipient ON messages (recipient_id, folder, created_at);
CREATE INDEX ix_messages_sender_group ON messages (sender_id, msg_group);
