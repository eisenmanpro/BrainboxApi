-- Admin School Config: the public school profile fields the landing card/dialog renders.
-- Additive and nullable: existing schools keep their current public payloads until an ICT
-- admin fills the fields in; public reads fall back to the existing school_details/config data.
ALTER TABLE school_configs ADD COLUMN website VARCHAR(255);
ALTER TABLE school_configs ADD COLUMN school_type VARCHAR(64);
ALTER TABLE school_configs ADD COLUMN school_hours VARCHAR(120);
ALTER TABLE school_configs ADD COLUMN about TEXT;
ALTER TABLE school_configs ADD COLUMN facilities TEXT;
ALTER TABLE school_configs ADD COLUMN highlights TEXT;
