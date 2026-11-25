-- One class per capture session per teacher (findings3.md paper-exams #6).
-- The web client locks the chosen class in sessionStorage; the server is the
-- authority. A coordinator editing their own grade is deliberately not locked.
CREATE TABLE IF NOT EXISTS traditional_capture_locks (
    id UUID PRIMARY KEY,
    exam_id UUID NOT NULL,
    teacher_id UUID NOT NULL,
    class_id UUID NOT NULL,
    class_tag VARCHAR(128) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_traditional_capture_lock UNIQUE (exam_id, teacher_id)
);

CREATE INDEX IF NOT EXISTS idx_traditional_capture_locks_exam
    ON traditional_capture_locks (exam_id);
