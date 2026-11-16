-- Optional link from a teacher's live class to the school class it targets.
--
-- The web schedule form picks one of the teacher's classes and the web's
-- attendance auto-mark filter reads this id back from GET /teacher/live-classes.
-- Android's LiveClass model has no such field, so the column is nullable and the
-- payload field is optional: an Android-authored session simply has no link, and
-- an Android edit of a web-authored session keeps the existing value.
ALTER TABLE live_classes ADD COLUMN IF NOT EXISTS school_class_id uuid;

CREATE INDEX IF NOT EXISTS ix_live_classes_school_class ON live_classes (school_class_id);
