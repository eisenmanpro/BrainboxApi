-- ============================================================
-- BrainboxApi V13: homework question sets (auto-grading follow-on)
-- Question keys live server-side; AUTO_IMMEDIATE grades on submit,
-- AUTO_POST_COMPLETION reveals when the due date passes.
-- ============================================================

CREATE TABLE homework_questions (
    id             uuid PRIMARY KEY,
    homework_id    varchar(128) NOT NULL REFERENCES homework (id) ON DELETE CASCADE,
    text           text NOT NULL,
    q_type         varchar(32) NOT NULL
                   CHECK (q_type IN ('MCQ','MULTI_SELECT','SHORT_ANSWER','ESSAY',
                                     'TRUE_FALSE','MATCHING','FILL_BLANK','NUMBER_ENTRY')),
    options        text,
    correct_answer text,
    explanation    text,
    points         integer NOT NULL DEFAULT 1,
    order_index    integer NOT NULL DEFAULT 0
);

CREATE INDEX ix_homework_questions_hw ON homework_questions (homework_id, order_index);

ALTER TABLE homework_submissions ADD COLUMN answers_json text;
