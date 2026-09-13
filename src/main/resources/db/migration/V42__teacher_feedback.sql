-- ============================================================
-- BrainboxApi V42: teacher feedback (doc 04 section 7)
-- Feedback writes and templates are offline-first on the client (built-in
-- teacher mutations outbox), so both use a unique client_id for replay.
-- ============================================================

CREATE TABLE teacher_feedback_templates (
    id         uuid PRIMARY KEY,
    client_id  varchar(80) NOT NULL,
    teacher_id uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    title      varchar(200) NOT NULL,
    content    text NOT NULL,
    category   varchar(64) NOT NULL,
    created_at timestamp with time zone NOT NULL DEFAULT now(),
    updated_at timestamp with time zone NOT NULL DEFAULT now(),
    version    bigint NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX uq_feedback_template_client ON teacher_feedback_templates (client_id);
CREATE INDEX ix_feedback_templates_teacher ON teacher_feedback_templates (teacher_id);

CREATE TABLE teacher_feedback (
    id                  uuid PRIMARY KEY,
    client_id           varchar(80) NOT NULL,
    teacher_id          uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    student_id          uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    submission_id       varchar(120),
    text_feedback       text,
    voice_feedback_url  varchar(512),
    photo_feedback_urls text,
    rubric_scores       text,
    created_at          timestamp with time zone NOT NULL DEFAULT now(),
    updated_at          timestamp with time zone NOT NULL DEFAULT now(),
    version             bigint NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX uq_teacher_feedback_client ON teacher_feedback (client_id);
CREATE INDEX ix_teacher_feedback_teacher ON teacher_feedback (teacher_id, created_at);
CREATE INDEX ix_teacher_feedback_student ON teacher_feedback (student_id, created_at);
