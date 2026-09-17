-- The practice-paper projection now carries the declarative figure spec next
-- to the rendered SVG, so a client can render the figure natively.
ALTER TABLE exam_questions ADD COLUMN figure_spec text;
