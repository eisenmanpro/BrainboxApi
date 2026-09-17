-- Phase 7.5 content JSON schema provenance: which contract produced the unit.
ALTER TABLE content_units ADD COLUMN content_schema_version varchar(32);
