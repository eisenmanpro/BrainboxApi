-- ============================================================
-- V58: traditional exam client-assigned identifiers
--
-- The Android client generates deterministic exam ids of the form
-- TRAD_<grade>_<OPENER|MID|END>_<year>_T<term> before it ever reaches the
-- server (core/calendar/ExamCalendarGenerator.kt). The server keeps its own
-- UUID primary key but stores that client id so exams, marks, confirmations,
-- analytics and edit requests can round-trip under the id the client knows.
--
-- Contract: BrainBox/docs/ongoing/traditional_exams_audit.md (TE-2/TE-3/TE-6).
-- ============================================================

ALTER TABLE traditional_exams ADD COLUMN client_exam_id varchar(128);

-- One exam per client id per school. Nullable client ids (legacy rows created
-- before this migration) stay unconstrained because NULLs are distinct.
CREATE UNIQUE INDEX uq_traditional_exams_client_id ON traditional_exams (school_id, client_exam_id);
