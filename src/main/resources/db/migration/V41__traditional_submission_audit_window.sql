-- Paper-exam submission, subset finalisation and audit window (web workflow round).
-- One ADD COLUMN per statement: the test profile uses H2, which rejects grouped additions.
ALTER TABLE traditional_exams ADD COLUMN IF NOT EXISTS audit_window_minutes INTEGER;
ALTER TABLE traditional_exams ADD COLUMN IF NOT EXISTS audit_deadline_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE traditional_exams ADD COLUMN IF NOT EXISTS included_class_tags TEXT;

ALTER TABLE traditional_capture_locks ADD COLUMN IF NOT EXISTS archived BOOLEAN DEFAULT FALSE;

-- Direct score corrections made by an ICT admin after publication. Every row is a
-- permanent audit record; the notification flags let an outbox replay retry fan-out.
CREATE TABLE IF NOT EXISTS traditional_published_edits (
    id UUID PRIMARY KEY,
    exam_id UUID NOT NULL,
    student_id UUID NOT NULL,
    actor_id UUID NOT NULL,
    old_score INTEGER NOT NULL,
    new_score INTEGER NOT NULL,
    edited_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    notified_learner BOOLEAN NOT NULL DEFAULT FALSE,
    notified_class_teacher BOOLEAN NOT NULL DEFAULT FALSE,
    notified_ict_admins BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE INDEX IF NOT EXISTS idx_traditional_published_edits_exam
    ON traditional_published_edits (exam_id);
