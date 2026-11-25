-- Audit 3 (learning tools): question source tagging and contest create idempotency.
--
-- 1. A question can now say WHERE it came from: authored here, reused from the
--    teacher's question bank, or lifted from a published practice paper. The
--    tag is stored, not just rendered, so the provenance survives a reload and
--    can be shown on every authoring surface.
-- 2. A teacher-authored contest stores the client id the browser generated, so
--    a retried create returns the same draft instead of a second copy (the old
--    title+grade guess could swallow a genuinely new contest).
--
-- H2 (tests) accepts one ADD COLUMN per statement and has no partial indexes;
-- a plain unique index still allows many NULL client ids on both engines, so
-- legacy rows are unaffected.

ALTER TABLE exam_questions ADD COLUMN IF NOT EXISTS source_kind varchar(32);
ALTER TABLE exam_questions ADD COLUMN IF NOT EXISTS source_ref varchar(160);
ALTER TABLE exam_questions ADD COLUMN IF NOT EXISTS source_label varchar(255);

ALTER TABLE teacher_question_bank ADD COLUMN IF NOT EXISTS source_kind varchar(32);
ALTER TABLE teacher_question_bank ADD COLUMN IF NOT EXISTS source_ref varchar(160);
ALTER TABLE teacher_question_bank ADD COLUMN IF NOT EXISTS source_label varchar(255);

ALTER TABLE contests ADD COLUMN IF NOT EXISTS client_id varchar(80);

-- A client id is unique per author: two authors may pick the same id string and
-- one author's replayed create can never resolve another author's contest.
-- NULL client ids (legacy rows) stay distinct on both Postgres and H2.
CREATE UNIQUE INDEX IF NOT EXISTS uq_contests_author_client
    ON contests (created_by, client_id);
