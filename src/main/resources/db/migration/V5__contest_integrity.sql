-- F4: server-computed integrity for contest submissions (telemetry arrives with the submit).
ALTER TABLE contest_submissions ADD COLUMN integrity_score INTEGER NOT NULL DEFAULT 100;
ALTER TABLE contest_submissions ADD COLUMN integrity_flags TEXT;
