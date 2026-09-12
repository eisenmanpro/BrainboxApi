-- ============================================================
-- BrainboxApi V35: teacher documents (readable_files teacher columns)
-- Contract: BrainBox/docs/ongoing/api_content_changes.md
-- ============================================================

ALTER TABLE readable_files ADD COLUMN client_id varchar(80);
ALTER TABLE readable_files ADD COLUMN description text;
ALTER TABLE readable_files ADD COLUMN author_name varchar(160);
ALTER TABLE readable_files ADD COLUMN topic varchar(255);
-- The client document type (PDF/EPUB/PLAINTEXT) when it exceeds FileType.
ALTER TABLE readable_files ADD COLUMN doc_type varchar(16);

CREATE UNIQUE INDEX uq_readable_files_client ON readable_files (client_id);
CREATE INDEX ix_readable_files_creator ON readable_files (created_by, is_active);
