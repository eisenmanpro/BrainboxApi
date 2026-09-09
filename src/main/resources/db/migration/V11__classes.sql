-- ============================================================
-- BrainboxApi V11: teacher classes & roster
-- Contract: docs/backend_contracts/04_... §2.2 + homework prerequisites.
-- Classes are owned by a teacher; memberships make the roster. Ownership
-- (teacher == owner) is enforced server-side on every class endpoint.
-- ============================================================

CREATE TABLE teacher_classes (
    id              uuid PRIMARY KEY,
    teacher_user_id uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    school_id       uuid REFERENCES schools (id),
    name            varchar(255) NOT NULL,
    grade_level     varchar(64)  NOT NULL,
    subject         varchar(128) NOT NULL,
    is_active       boolean NOT NULL DEFAULT TRUE,
    created_at      timestamp with time zone NOT NULL DEFAULT now(),
    updated_at      timestamp with time zone NOT NULL DEFAULT now(),
    version         bigint NOT NULL DEFAULT 0
);

CREATE INDEX ix_teacher_classes_teacher ON teacher_classes (teacher_user_id);

CREATE TABLE class_memberships (
    id         uuid PRIMARY KEY,
    class_id   uuid NOT NULL REFERENCES teacher_classes (id) ON DELETE CASCADE,
    student_id uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    joined_at  timestamp with time zone NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uq_class_membership ON class_memberships (class_id, student_id);
CREATE INDEX ix_class_membership_student ON class_memberships (student_id);
