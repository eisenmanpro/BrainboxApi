-- Phase 7.5 server-rendered figures. A unit step or question stores the
-- declarative spec the model authored plus the SVG the server rendered from it;
-- an exam question only needs the rendered SVG for its practice-paper section.
ALTER TABLE content_unit_steps ADD COLUMN figure_spec text;
ALTER TABLE content_unit_questions ADD COLUMN figure_spec text;
ALTER TABLE content_unit_questions ADD COLUMN figure_svg text;
ALTER TABLE exam_questions ADD COLUMN figure_svg text;
