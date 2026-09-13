-- ============================================================
-- BrainboxApi V63: content projection into the client-facing tables
-- Phase 7.3: the generated/uploaded content_units cache is projected onto the
-- tables the client already reads. A book-like unit (NOTES/BOOK/QUIZ/FLASHCARDS)
-- becomes a learning_posts row plus learning_content blocks; a CHUNK becomes a
-- readable_files row with an inline body. The shared concept layer is seeded from
-- the Kenya CBC strand catalogue (V45) so one concept can be re-localised later.
--
-- Deterministic and idempotent-safe: the cbc_strands UUID is reused as the
-- concepts.id and the curriculum_map.id (different tables), and each seed insert
-- is guarded by NOT EXISTS so a re-run on a fresh database cannot duplicate rows.
-- H2 (PostgreSQL mode) and PostgreSQL compatible.
-- ============================================================

ALTER TABLE content_units ADD COLUMN title varchar(255);
ALTER TABLE readable_files ADD COLUMN body text;

-- The CBC strand catalogue is the first national mapping of the shared concept
-- layer: one concept per strand, keyed by the strand's stable UUID and coded
-- 'CBC-<strand code>'.
INSERT INTO concepts (id, code, name, description, subject, parent_id, sort_order)
SELECT s.id, 'CBC-' || s.code, s.name, s.descriptor, s.subject, NULL, s.sort_order
FROM cbc_strands s
WHERE NOT EXISTS (SELECT 1 FROM concepts c WHERE c.id = s.id);

-- Each seeded concept maps back to its Kenya CBC strand. The same UUID is reused
-- because the two tables are independent.
INSERT INTO curriculum_map (
    id, concept_id, country_code, curriculum, grade_level,
    strand_code, strand_name, substrand_code, substrand_name, learning_outcome, sort_order
)
SELECT s.id, s.id, 'KE', 'CBC', s.grade_level, s.code, s.name, NULL, NULL, s.descriptor, s.sort_order
FROM cbc_strands s
WHERE NOT EXISTS (SELECT 1 FROM curriculum_map m WHERE m.id = s.id);
