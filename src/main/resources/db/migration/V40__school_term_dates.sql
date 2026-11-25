-- Exact term windows alongside the existing academicCalendar labels. The labels
-- stay the report-facing names (Term 1/2/3); these dates let every surface resolve
-- the real current term instead of guessing from the month.
ALTER TABLE school_configs ADD COLUMN term_dates text;
