-- Phase 7.5 scope inheritance: generated content can be GLOBAL, SCHOOL or
-- SCHOOL_GRADE_CLASS, so the existing visibility rules apply to it too.
ALTER TABLE content_units ADD COLUMN scope varchar(24) NOT NULL DEFAULT 'GLOBAL';
ALTER TABLE content_units ADD COLUMN school_id uuid;
