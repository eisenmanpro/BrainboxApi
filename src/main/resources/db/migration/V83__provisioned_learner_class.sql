-- Roster-only learners are deliberately NOT class_memberships: that is what
-- keeps them out of attendance, gradebook, homework, CBC analytics, messaging
-- fan-out and every other class/grade feature by construction. Their class is
-- recorded here only so traditional per-class reports can tag them correctly.
ALTER TABLE users ADD COLUMN provisioned_class_id uuid REFERENCES teacher_classes (id) ON DELETE SET NULL;
CREATE INDEX ix_users_provisioned_class ON users (provisioned_class_id);
