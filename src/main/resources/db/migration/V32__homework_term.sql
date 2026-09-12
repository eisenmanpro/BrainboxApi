-- ============================================================
-- BrainboxApi V32: homework term tag
-- docs/ongoing/api_gradebook_changes.md section 2.2: the gradebook term filter
-- must cover homework, so homework carries an explicit term (month fallback for
-- legacy rows).
-- ============================================================

ALTER TABLE homework ADD COLUMN term varchar(16);
