-- D16/A23: the school's own stamp used on reports. NULL means "use the standard
-- Exam Office stamp", so every existing school keeps today's behaviour.
ALTER TABLE school_configs ADD COLUMN stamp_url VARCHAR(512);
