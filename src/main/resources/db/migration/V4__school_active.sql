-- V4: school active flag (admin DELETE = deactivate, doc 01 §9.2)
ALTER TABLE schools
    ADD COLUMN is_active boolean NOT NULL DEFAULT TRUE;
