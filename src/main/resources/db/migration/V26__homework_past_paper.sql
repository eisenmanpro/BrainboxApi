-- ============================================================
-- BrainboxApi V26: homework attachments & past-paper linking
-- Contract: BrainboxWeb/docs/backend_contract_homework.md
-- ============================================================

ALTER TABLE homework ADD COLUMN related_paper_code varchar(64);
ALTER TABLE homework ADD COLUMN related_document_id varchar(64);
