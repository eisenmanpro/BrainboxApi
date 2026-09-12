-- ============================================================
-- BrainboxApi V30: teacher attendance register
-- Contract: BrainBox/docs/ongoing/api_attendance_changes.md and doc 04 §4
-- ============================================================

CREATE TABLE attendance_records (
    id                      uuid PRIMARY KEY,
    class_id                uuid NOT NULL REFERENCES teacher_classes (id) ON DELETE CASCADE,
    student_id              uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    school_id               uuid,
    attendance_date         date NOT NULL,
    status                  varchar(16) NOT NULL,
    notes                   text,
    recorded_by             uuid,
    recorded_by_name        varchar(160),
    is_auto_from_live_class boolean NOT NULL DEFAULT FALSE,
    created_at              timestamp with time zone NOT NULL DEFAULT now(),
    updated_at              timestamp with time zone NOT NULL DEFAULT now(),
    version                 bigint NOT NULL DEFAULT 0,
    CONSTRAINT ck_attendance_status CHECK (status IN ('PRESENT','ABSENT','LATE','EXCUSED'))
);

-- A register is idempotent per (class, calendar day, student); replayed offline
-- registers converge instead of duplicating.
CREATE UNIQUE INDEX uq_attendance_record ON attendance_records (class_id, attendance_date, student_id);
CREATE INDEX ix_attendance_class_date ON attendance_records (class_id, attendance_date);
CREATE INDEX ix_attendance_student_date ON attendance_records (student_id, attendance_date);
