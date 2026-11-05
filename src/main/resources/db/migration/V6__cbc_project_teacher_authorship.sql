ALTER TABLE cbc_projects ADD COLUMN IF NOT EXISTS student_ids TEXT;
ALTER TABLE cbc_projects ADD COLUMN IF NOT EXISTS show_learner_names TEXT;
ALTER TABLE cbc_projects ADD COLUMN IF NOT EXISTS submitter_id UUID;
ALTER TABLE cbc_projects ADD COLUMN IF NOT EXISTS moderation_note TEXT;
