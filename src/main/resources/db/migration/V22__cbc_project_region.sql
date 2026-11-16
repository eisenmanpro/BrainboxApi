-- PRJ-05: the CBC project showcase is positioned as pan-African, but the public
-- payload had no region at all. Country is the filterable unit ("across the
-- continent"); county is the Kenyan sub-region schools already have and gives the
-- browse surface something concrete for local discovery. Both are optional and
-- additive; a project without them renders honestly with no region claim.
ALTER TABLE cbc_projects ADD COLUMN IF NOT EXISTS country varchar(64);
ALTER TABLE cbc_projects ADD COLUMN IF NOT EXISTS county varchar(128);

CREATE INDEX IF NOT EXISTS ix_cbc_projects_country ON cbc_projects (country);
CREATE INDEX IF NOT EXISTS ix_cbc_projects_county ON cbc_projects (county);
