-- ============================================================
-- BrainboxApi V25: class groups become scoped chat communities.
--
-- Product rules:
--  * a group is either CLASS-scoped (one class, the class teacher's group) or
--    GRADE-scoped (one grade of the school, joinable by teachers in the audience);
--  * the creator picks the audience: STUDENTS, TEACHERS, CLASS_TEACHERS or ALL;
--  * a school admin (ICT_ADMIN) can list every group of the school, join and
--    contribute for moderation, and freeze a group with a reason; freeze is
--    reversible and blocks writes while it lasts;
--  * term archive is a separate, reversible read-only state (admin-owned);
--  * a class group keeps its class FK; a grade group has no class.
-- ============================================================

ALTER TABLE class_groups ADD COLUMN scope varchar(16) NOT NULL DEFAULT 'CLASS';
ALTER TABLE class_groups ADD COLUMN grade_level varchar(64);
ALTER TABLE class_groups ADD COLUMN audience varchar(24) NOT NULL DEFAULT 'STUDENTS';
ALTER TABLE class_groups ADD COLUMN school_id uuid;
ALTER TABLE class_groups ADD COLUMN is_frozen boolean NOT NULL DEFAULT FALSE;
ALTER TABLE class_groups ADD COLUMN frozen_reason varchar(500);
ALTER TABLE class_groups ADD COLUMN frozen_by uuid;
ALTER TABLE class_groups ADD COLUMN frozen_at timestamp with time zone;
ALTER TABLE class_groups ADD COLUMN is_archived boolean NOT NULL DEFAULT FALSE;
ALTER TABLE class_groups ADD COLUMN archived_at timestamp with time zone;
ALTER TABLE class_groups ADD COLUMN archive_term_label varchar(64);
ALTER TABLE class_groups ADD COLUMN archive_reason varchar(500);

-- A grade-level group has no class; a class-level group keeps one.
ALTER TABLE class_groups ALTER COLUMN class_id DROP NOT NULL;

-- Every existing group belongs to the school of its class. New rows always set
-- school_id from the class or the creator's school.
UPDATE class_groups
SET school_id = (
    SELECT c.school_id FROM teacher_classes c WHERE c.id = class_groups.class_id
)
WHERE school_id IS NULL;

CREATE INDEX ix_class_groups_school ON class_groups (school_id, updated_at);
CREATE INDEX ix_class_groups_school_grade ON class_groups (school_id, grade_level);
