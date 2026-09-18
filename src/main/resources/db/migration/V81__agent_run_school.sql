-- H3 cost budget: attribute a generation/verification/critique run to the
-- school that requested it, so per-school spend is a cheap indexed join and
-- platform-scope work (school_id null) is charged to the platform only.
ALTER TABLE agent_runs ADD COLUMN school_id uuid;
CREATE INDEX ix_agent_runs_school ON agent_runs (school_id, created_at);
