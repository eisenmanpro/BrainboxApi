-- Student grade/stream transitions (docs/ongoing/product_ops_roadmap.md item 3).
-- Streams become explicit so a class can be filtered and a move can be recorded.
-- One ADD COLUMN per statement: H2 rejects the multi-column form Postgres allows.

ALTER TABLE teacher_classes ADD COLUMN stream varchar(64);

CREATE TABLE student_class_transitions (
    id            uuid PRIMARY KEY,
    student_id    uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    from_class_id uuid REFERENCES teacher_classes (id) ON DELETE SET NULL,
    to_class_id   uuid NOT NULL REFERENCES teacher_classes (id) ON DELETE CASCADE,
    mode          varchar(8) NOT NULL CHECK (mode IN ('PULL', 'PUSH', 'PROMOTE')),
    reason        varchar(500),
    initiated_by  uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    school_id     uuid,
    created_at    timestamp with time zone NOT NULL DEFAULT now()
);

CREATE INDEX ix_transition_student ON student_class_transitions (student_id, created_at);
CREATE INDEX ix_transition_school ON student_class_transitions (school_id, created_at);

-- Coordinator on/off switch for grade transitioning (product_ops_roadmap item 4).
ALTER TABLE school_system_settings ADD COLUMN transitions_enabled boolean NOT NULL DEFAULT true;
