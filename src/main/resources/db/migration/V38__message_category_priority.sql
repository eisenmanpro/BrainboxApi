-- Message categories + priority: the client has sent both since the messages
-- rebuild, but the server dropped them, so every filter chip fell back to
-- "General" after a refresh (the local cache kept the chosen category only
-- until the next read). Persist them per message copy.
ALTER TABLE messages ADD COLUMN category varchar(24) NOT NULL DEFAULT 'GENERAL';
ALTER TABLE messages ADD COLUMN is_priority boolean NOT NULL DEFAULT false;
