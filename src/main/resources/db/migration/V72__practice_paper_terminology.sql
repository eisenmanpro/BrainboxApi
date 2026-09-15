-- ============================================================
-- BrainboxApi V72: practice-paper terminology
--
-- Brainbox generates its own practice papers and never reproduces KNEC
-- examination papers. The stored exam type and homework submission type move
-- from the earlier wording to PRACTICE_PAPER. V6/V12 are already applied, so
-- their inline CHECK constraints cannot be edited in place; each old check is
-- dropped by both its PostgreSQL name (<table>_<column>_check) and its H2
-- generated name (content-derived and stable for the pinned H2 2.4.240), the
-- rows are migrated, and a named check replaces it.
--
-- There is no past-paper-specific table: the term lived in the exams.exam_type
-- and homework.submission_type enum values, the homework link column and the
-- HTTP routes. Only the column and the two CHECKs need a schema change.
-- ============================================================

-- exams.exam_type: PAST_PAPER -> PRACTICE_PAPER
ALTER TABLE exams DROP CONSTRAINT IF EXISTS exams_exam_type_check;
ALTER TABLE exams DROP CONSTRAINT IF EXISTS "CONSTRAINT_5C7";
UPDATE exams SET exam_type = 'PRACTICE_PAPER' WHERE exam_type = 'PAST_PAPER';
ALTER TABLE exams ADD CONSTRAINT ck_exams_exam_type
    CHECK (exam_type IN ('DIGITAL','PRACTICE_PAPER','TRADITIONAL','QUIZ'));

-- homework.submission_type: PAST_PAPER_REVIEW -> PRACTICE_PAPER_REVIEW
ALTER TABLE homework DROP CONSTRAINT IF EXISTS homework_submission_type_check;
ALTER TABLE homework DROP CONSTRAINT IF EXISTS "CONSTRAINT_E31534";
UPDATE homework SET submission_type = 'PRACTICE_PAPER_REVIEW' WHERE submission_type = 'PAST_PAPER_REVIEW';
ALTER TABLE homework ADD CONSTRAINT ck_homework_submission_type
    CHECK (submission_type IN ('FREE_TEXT','PRACTICE_PAPER_REVIEW','EXAM_QUESTION_SET','CHECKLIST','OFFLINE_PHYSICAL_HANDIN'));

-- homework unlocked flag renamed to is_practice_paper_unlocked
ALTER TABLE homework RENAME COLUMN is_past_paper_unlocked TO is_practice_paper_unlocked;
