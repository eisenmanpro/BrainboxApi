-- School-owned landing gallery (findings "ADMIN SCHOOL CONFIG 2").
--
-- An ICT admin uploads up to four images for their school's landing card; when the list is
-- non-empty it overrides the platform editorial gallery (schools.image_urls). Stored as a
-- JSON array on the config row the branding editor already owns.
ALTER TABLE school_configs ADD COLUMN IF NOT EXISTS gallery_urls TEXT;
