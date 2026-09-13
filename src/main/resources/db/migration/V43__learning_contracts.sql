-- ============================================================
-- BrainboxApi V43: teacher learning contracts (doc 04 section 11)
-- Contract CRUD + per-commitment completion, reminder fan-out and the canonical
-- template catalogue. Client ids make the offline outbox replay idempotent.
-- ============================================================

CREATE TABLE learning_contracts (
    id           uuid PRIMARY KEY,
    client_id    varchar(80) NOT NULL,
    teacher_id   uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    child_id     uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    term         varchar(32) NOT NULL,
    status       varchar(16) NOT NULL DEFAULT 'ACTIVE'
                 CHECK (status IN ('ACTIVE','COMPLETED','EXPIRED')),
    start_date   timestamp with time zone NOT NULL,
    end_date     timestamp with time zone NOT NULL,
    last_updated timestamp with time zone NOT NULL,
    created_at   timestamp with time zone NOT NULL DEFAULT now(),
    updated_at   timestamp with time zone NOT NULL DEFAULT now(),
    version      bigint NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX uq_learning_contract_client ON learning_contracts (client_id);
CREATE INDEX ix_learning_contracts_teacher ON learning_contracts (teacher_id);
CREATE INDEX ix_learning_contracts_child ON learning_contracts (child_id);

CREATE TABLE learning_contract_commitments (
    id              uuid PRIMARY KEY,
    client_id       varchar(80) NOT NULL,
    contract_id     uuid NOT NULL REFERENCES learning_contracts (id) ON DELETE CASCADE,
    party           varchar(16) NOT NULL,
    text            text NOT NULL,
    is_completed    boolean NOT NULL DEFAULT FALSE,
    due_date        timestamp with time zone,
    notes           text,
    completion_date timestamp with time zone,
    sort_order      integer NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX uq_contract_commitment_client ON learning_contract_commitments (contract_id, client_id);
CREATE INDEX ix_contract_commitments_contract ON learning_contract_commitments (contract_id, sort_order);

CREATE TABLE contract_templates (
    id                  uuid PRIMARY KEY,
    title               varchar(200) NOT NULL,
    description         text NOT NULL,
    category            varchar(64) NOT NULL,
    default_commitments text,
    created_at          timestamp with time zone NOT NULL DEFAULT now(),
    updated_at          timestamp with time zone NOT NULL DEFAULT now(),
    version             bigint NOT NULL DEFAULT 0
);

INSERT INTO contract_templates (id, title, description, category, default_commitments) VALUES
('11111111-1111-4111-8111-111111111101', 'Academic Improvement Plan',
 'A focused plan to lift a learner''s grades over the term.', 'ACADEMIC',
 '[{"id":"c1","party":"STUDENT","text":"Attend every class and take notes"},{"id":"c2","party":"PARENT","text":"Check homework and revision weekly"},{"id":"c3","party":"TEACHER","text":"Share a progress note every two weeks"}]'),
('11111111-1111-4111-8111-111111111102', 'Assignment Completion Contract',
 'Ensures homework is set, done and returned on time.', 'HOMEWORK',
 '[{"id":"c1","party":"TEACHER","text":"Post assignments by Monday"},{"id":"c2","party":"STUDENT","text":"Submit all assignments before the deadline"},{"id":"c3","party":"PARENT","text":"Confirm submissions are complete"}]'),
('11111111-1111-4111-8111-111111111103', 'Engagement & Behaviour Contract',
 'Improves class participation and positive behaviour.', 'ENGAGEMENT',
 '[{"id":"c1","party":"STUDENT","text":"Participate in at least one class discussion a day"},{"id":"c2","party":"PARENT","text":"Attend a monthly check-in with the teacher"},{"id":"c3","party":"TEACHER","text":"Recognise good participation weekly"}]');
