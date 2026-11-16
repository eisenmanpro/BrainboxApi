-- Structured announcement targeting (findings "ADMIN ANNOUNCEMENTS 1").
--
-- A school announcement can now name its audience by grade / class / individual teacher /
-- individual learner instead of a free-text audience string. The legacy "audience" column is
-- kept and still honoured when the structured fields are absent.
ALTER TABLE school_announcements ADD COLUMN IF NOT EXISTS target_type VARCHAR(24);
ALTER TABLE school_announcements ADD COLUMN IF NOT EXISTS target_grade_levels TEXT;
ALTER TABLE school_announcements ADD COLUMN IF NOT EXISTS target_class_ids TEXT;
ALTER TABLE school_announcements ADD COLUMN IF NOT EXISTS target_teacher_ids TEXT;
ALTER TABLE school_announcements ADD COLUMN IF NOT EXISTS target_student_ids TEXT;

-- The delivery channel learns the two school-only targets: explicit staff ids and the
-- originating school announcement (so an admin edit refreshes the copy instead of duplicating).
ALTER TABLE teacher_announcements ADD COLUMN IF NOT EXISTS target_teacher_ids TEXT;
ALTER TABLE teacher_announcements ADD COLUMN IF NOT EXISTS source_school_announcement_id UUID;

CREATE INDEX IF NOT EXISTS ix_teacher_announcements_source_school
    ON teacher_announcements (source_school_announcement_id);
