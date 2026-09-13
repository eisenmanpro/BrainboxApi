-- ============================================================
-- BrainboxApi V61: content concept layer + generation cache
-- Phase 7.1: the concept catalogue, its curriculum map, generated/uploaded
-- content units (with steps and questions) and the generation job queue.
-- H2 (PostgreSQL mode) and PostgreSQL compatible: no partial indexes and no
-- now()+interval arithmetic; UUID ids and per-row timestamps/version come from
-- BaseEntity (created_at, updated_at, version).
-- ============================================================

CREATE TABLE concepts (
    id          uuid PRIMARY KEY,
    code        varchar(64) NOT NULL,
    name        varchar(200) NOT NULL,
    description text,
    subject     varchar(64) NOT NULL,
    parent_id   uuid REFERENCES concepts (id) ON DELETE SET NULL,
    sort_order  integer NOT NULL DEFAULT 0,
    created_at  timestamp with time zone NOT NULL DEFAULT now(),
    updated_at  timestamp with time zone NOT NULL DEFAULT now(),
    version     bigint NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX uq_concepts_code ON concepts (code);

CREATE TABLE curriculum_map (
    id               uuid PRIMARY KEY,
    concept_id       uuid NOT NULL REFERENCES concepts (id) ON DELETE CASCADE,
    country_code     varchar(8) NOT NULL,
    curriculum       varchar(32) NOT NULL,
    grade_level      varchar(32) NOT NULL,
    strand_code      varchar(64),
    strand_name      varchar(160),
    substrand_code   varchar(64),
    substrand_name   varchar(160),
    learning_outcome text,
    sort_order       integer NOT NULL DEFAULT 0,
    created_at       timestamp with time zone NOT NULL DEFAULT now(),
    updated_at       timestamp with time zone NOT NULL DEFAULT now(),
    version          bigint NOT NULL DEFAULT 0
);

CREATE INDEX ix_curriculum_map_lookup
    ON curriculum_map (country_code, curriculum, grade_level, strand_code, substrand_code);

CREATE TABLE content_units (
    id               uuid PRIMARY KEY,
    generation_key   varchar(256) NOT NULL,
    task_type        varchar(32) NOT NULL,
    concept_id       uuid REFERENCES concepts (id) ON DELETE SET NULL,
    subject          varchar(64) NOT NULL,
    grade_level      varchar(32) NOT NULL,
    language         varchar(8) NOT NULL DEFAULT 'en',
    standard_version varchar(16) NOT NULL DEFAULT 'v1',
    schema_version   varchar(16) NOT NULL DEFAULT 'v1',
    prompt_version   varchar(32),
    body             text,
    provenance       varchar(16) NOT NULL DEFAULT 'GENERATED'
                     CHECK (provenance IN ('GENERATED','UPLOADED')),
    author_name      varchar(160),
    source_urls      text,
    license          varchar(64),
    model            varchar(64),
    tokens           integer NOT NULL DEFAULT 0,
    confidence       double precision,
    review_state     varchar(16) NOT NULL DEFAULT 'UNREVIEWED'
                     CHECK (review_state IN ('UNREVIEWED','REVIEWED','REJECTED')),
    status           varchar(16) NOT NULL DEFAULT 'DRAFT',
    published_at     timestamp with time zone,
    created_at       timestamp with time zone NOT NULL DEFAULT now(),
    updated_at       timestamp with time zone NOT NULL DEFAULT now(),
    version          bigint NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX uq_content_units_generation_key ON content_units (generation_key);
CREATE INDEX ix_content_units_lookup
    ON content_units (concept_id, grade_level, task_type, review_state);

CREATE TABLE content_unit_steps (
    id          uuid PRIMARY KEY,
    unit_id     uuid NOT NULL REFERENCES content_units (id) ON DELETE CASCADE,
    order_index integer NOT NULL DEFAULT 0,
    title       varchar(200),
    body        text,
    figure_svg  text,
    figure_url  varchar(512),
    created_at  timestamp with time zone NOT NULL DEFAULT now(),
    updated_at  timestamp with time zone NOT NULL DEFAULT now(),
    version     bigint NOT NULL DEFAULT 0
);

CREATE INDEX ix_content_unit_steps_order ON content_unit_steps (unit_id, order_index);

CREATE TABLE content_unit_questions (
    id             uuid PRIMARY KEY,
    unit_id        uuid NOT NULL REFERENCES content_units (id) ON DELETE CASCADE,
    step_id        uuid REFERENCES content_unit_steps (id) ON DELETE SET NULL,
    order_index    integer NOT NULL DEFAULT 0,
    q_type         varchar(24) NOT NULL,
    text           text NOT NULL,
    options        text,
    correct_answer text,
    explanation    text,
    points         integer NOT NULL DEFAULT 1,
    difficulty     integer NOT NULL DEFAULT 3,
    matching_pairs text,
    created_at     timestamp with time zone NOT NULL DEFAULT now(),
    updated_at     timestamp with time zone NOT NULL DEFAULT now(),
    version        bigint NOT NULL DEFAULT 0
);

CREATE INDEX ix_content_unit_questions_order ON content_unit_questions (unit_id, order_index);

CREATE TABLE generation_jobs (
    id             uuid PRIMARY KEY,
    generation_key varchar(256) NOT NULL,
    task_type      varchar(32) NOT NULL,
    concept_id     uuid REFERENCES concepts (id) ON DELETE SET NULL,
    grade_level    varchar(32) NOT NULL,
    status         varchar(16) NOT NULL DEFAULT 'QUEUED',
    attempts       integer NOT NULL DEFAULT 0,
    last_error     text,
    run_id         uuid,
    created_at     timestamp with time zone NOT NULL DEFAULT now(),
    updated_at     timestamp with time zone NOT NULL DEFAULT now(),
    version        bigint NOT NULL DEFAULT 0
);

CREATE INDEX ix_generation_jobs_status ON generation_jobs (status, created_at);
