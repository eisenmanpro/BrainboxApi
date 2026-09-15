-- ============================================================
-- BrainboxApi V69: per-question disposition of disputed answer keys (Phase 7.5h)
-- The independent answer-key solve (V68) recorded a whole-unit agreement ratio, so
-- one or two bad items discarded an otherwise accurate quiz. The disposition is now
-- per question: drop the disputed items, keep the rest, and publish only when every
-- surviving key agrees and the question floor is still met.
-- answer_key_dropped counts the items removed from content_unit_questions;
-- answer_key_dropped_detail is the JSON audit of what was removed
-- ({orderIndex, text, storedKey, verifiedAnswer}). Both columns are additive and
-- default to the empty disposition, and the types pass ddl-auto: validate on H2
-- (PostgreSQL mode) and PostgreSQL.
-- ============================================================

ALTER TABLE content_units ADD COLUMN answer_key_dropped integer NOT NULL DEFAULT 0;
ALTER TABLE content_units ADD COLUMN answer_key_dropped_detail text;
