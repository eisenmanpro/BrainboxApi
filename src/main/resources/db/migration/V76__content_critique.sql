-- Phase 7.5 LLM critic: a separate pedagogy judgement on the assembled unit,
-- alongside the deterministic validators and the independent answer-key solve.
ALTER TABLE content_units ADD COLUMN critique_score double precision;
ALTER TABLE content_units ADD COLUMN critique_at timestamp with time zone;
ALTER TABLE content_units ADD COLUMN critique_model varchar(64);
ALTER TABLE content_units ADD COLUMN critique_findings text;
