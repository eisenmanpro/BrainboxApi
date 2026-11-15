-- D18/decision 20: level bands are a capture-session setting.
-- The bands resolved when the session is created are snapshotted on the exam so a later
-- change to the grade's last-used config never re-bands an old exam or its reports.
ALTER TABLE traditional_exams ADD COLUMN IF NOT EXISTS grading_snapshot TEXT;
