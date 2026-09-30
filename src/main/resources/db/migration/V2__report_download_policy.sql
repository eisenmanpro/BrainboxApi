-- Report download policy (docs/ongoing/product_ops_roadmap.md item 1):
-- classify each download as a metered per-student export or a free aggregate
-- export, and record how many learners a single download covered.
--
-- NOTE: one ADD COLUMN per statement. H2 (the test database) rejects the
-- multi-column ALTER TABLE form that Postgres accepts.

ALTER TABLE report_downloads ADD COLUMN scope varchar(16) NOT NULL DEFAULT 'FREE';
ALTER TABLE report_downloads ADD COLUMN student_count integer NOT NULL DEFAULT 1;

ALTER TABLE report_downloads
    ADD CONSTRAINT ck_report_downloads_scope CHECK (scope IN ('FREE', 'STUDENT'));

CREATE INDEX ix_report_downloads_owner_scope ON report_downloads (owner_id, scope);

-- How many learners a job covers, so the download path can tell a single-student
-- export from a coverage-gated bulk job.
ALTER TABLE report_jobs ADD COLUMN student_count integer NOT NULL DEFAULT 1;
