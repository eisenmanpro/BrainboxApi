-- ============================================================
-- BrainboxApi V47: class-group poll client id
-- docs/ongoing/backend_conformance.md: an offline poll-create replay carries a
-- stable clientPollId, so the server upserts by it instead of duplicating.
-- ============================================================

ALTER TABLE class_group_polls ADD COLUMN client_id varchar(80);
CREATE UNIQUE INDEX uq_class_group_poll_client ON class_group_polls (client_id);
