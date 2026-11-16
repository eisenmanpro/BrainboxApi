-- ============================================================
-- BrainboxApi V27: specialized homework
--
-- Findings round 2 (homework manager): the web Specialized tab had no creation
-- path. A specialized assignment is a targeted intervention for named learners
-- with a stated focus; it keeps a normal submission type so the learner
-- experience is unchanged. KIND is orthogonal to scope/submission_type.
--
-- Invariants (enforced in TeacherHomeworkService, not by CHECK because drafts may
-- be saved before the teacher finishes them):
--   * kind = SPECIALIZED needs >=1 targeted learner (assigned_student_ids) and a
--     5-280 character specialization_reason once the homework is published;
--   * a SPECIALIZED homework is always class-scoped.
-- ============================================================

ALTER TABLE homework ADD COLUMN kind varchar(24) NOT NULL DEFAULT 'REGULAR';
ALTER TABLE homework ADD COLUMN specialization_reason varchar(280);

ALTER TABLE homework ADD CONSTRAINT ck_homework_kind
    CHECK (kind IN ('REGULAR', 'SPECIALIZED'));

-- The teacher's manager filters a strip of specialized homework; keep that read cheap.
CREATE INDEX ix_homework_teacher_kind ON homework (teacher_id, kind);
