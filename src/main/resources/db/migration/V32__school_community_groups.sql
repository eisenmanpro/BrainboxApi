-- Audit 3: every school gets two system-managed community groups.
ALTER TABLE class_groups ADD COLUMN is_system boolean NOT NULL DEFAULT FALSE;

-- H2 (tests) has no partial indexes; the service lookup is the uniqueness guard.
CREATE INDEX ix_class_groups_system ON class_groups (school_id, scope, audience);
