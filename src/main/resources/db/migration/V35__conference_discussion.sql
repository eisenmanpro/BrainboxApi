-- Audit 3: the conference "meeting discussion" is a real, server-persisted
-- thread per slot (the local audit log stays client-only). Messages carry a
-- client id so an offline write replay upserts instead of duplicating.

CREATE TABLE IF NOT EXISTS conference_discussions (
    id           uuid PRIMARY KEY,
    client_id    varchar(80) NOT NULL,
    slot_id      uuid NOT NULL REFERENCES conference_slots (id) ON DELETE CASCADE,
    author_id    uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    author_name  varchar(160),
    author_role  varchar(32) NOT NULL DEFAULT 'TEACHER',
    body         text NOT NULL,
    created_at   timestamp with time zone NOT NULL DEFAULT now(),
    updated_at   timestamp with time zone NOT NULL DEFAULT now(),
    version      bigint NOT NULL DEFAULT 0
);

-- A client id is unique inside its slot, never globally: the replay key is
-- (slot_id, client_id), so two slots can never resolve each other's message.
CREATE UNIQUE INDEX IF NOT EXISTS uq_conference_discussion_slot_client
    ON conference_discussions (slot_id, client_id);
CREATE INDEX IF NOT EXISTS ix_conference_discussions_slot
    ON conference_discussions (slot_id, created_at);
