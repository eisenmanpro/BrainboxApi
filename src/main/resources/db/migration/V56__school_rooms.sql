-- ============================================================
-- BrainboxApi V56: school room catalogue (TT-3)
-- SchoolConfig.rooms is the server-owned room list the client room picker
-- reads. Stored as a JSON list like academic_calendar/cbc_strands.
-- ============================================================

ALTER TABLE school_configs ADD COLUMN rooms text;
