-- ============================================================
-- BrainboxApi V62: content capture tables + generation runner
-- Phase 7.2: cache-first content router capture rows. The router is the only
-- writer of these tables; prompts are versioned so eval scores and feedback can
-- be attributed later. H2 (PostgreSQL mode) and PostgreSQL compatible: UUID ids
-- and per-row timestamps/version come from BaseEntity.
--
-- NOTE on prompt_versions: the semantic prompt version is stored in
-- prompt_version (varchar 32) rather than "version" because BaseEntity already
-- owns the optimistic-lock "version" bigint column. The task's "version
-- varchar(32)" and "version bigint DEFAULT 0" cannot coexist as one column name;
-- this keeps the required optimistic-lock schema uniform across tables.
-- ============================================================

CREATE TABLE prompt_versions (
    id             uuid PRIMARY KEY,
    prompt_key     varchar(128) NOT NULL,
    prompt_version varchar(32) NOT NULL,
    body           text NOT NULL,
    eval_score     double precision,
    is_active      boolean NOT NULL DEFAULT FALSE,
    created_at     timestamp with time zone NOT NULL DEFAULT now(),
    updated_at     timestamp with time zone NOT NULL DEFAULT now(),
    version        bigint NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX uq_prompt_versions_key_version ON prompt_versions (prompt_key, prompt_version);
CREATE INDEX ix_prompt_versions_active ON prompt_versions (prompt_key, is_active);

CREATE TABLE agent_runs (
    id             uuid PRIMARY KEY,
    job_id         uuid REFERENCES generation_jobs (id) ON DELETE CASCADE,
    generation_key varchar(256) NOT NULL,
    prompt_version varchar(32),
    model          varchar(64),
    iterations     integer NOT NULL DEFAULT 0,
    confidence     double precision,
    status         varchar(16) NOT NULL DEFAULT 'RUNNING',
    created_at     timestamp with time zone NOT NULL DEFAULT now(),
    updated_at     timestamp with time zone NOT NULL DEFAULT now(),
    version        bigint NOT NULL DEFAULT 0
);

CREATE INDEX ix_agent_runs_generation_key ON agent_runs (generation_key);

CREATE TABLE model_calls (
    id                uuid PRIMARY KEY,
    agent_run_id      uuid NOT NULL REFERENCES agent_runs (id) ON DELETE CASCADE,
    provider          varchar(32) NOT NULL,
    model             varchar(64),
    prompt_tokens     integer NOT NULL DEFAULT 0,
    completion_tokens integer NOT NULL DEFAULT 0,
    cost_micros       bigint NOT NULL DEFAULT 0,
    latency_ms        bigint NOT NULL DEFAULT 0,
    success           boolean NOT NULL DEFAULT TRUE,
    error             text,
    created_at        timestamp with time zone NOT NULL DEFAULT now()
);

CREATE INDEX ix_model_calls_agent_run ON model_calls (agent_run_id);

CREATE TABLE tool_calls (
    id            uuid PRIMARY KEY,
    agent_run_id  uuid NOT NULL REFERENCES agent_runs (id) ON DELETE CASCADE,
    tool_name     varchar(64) NOT NULL,
    success       boolean NOT NULL DEFAULT TRUE,
    latency_ms    bigint NOT NULL DEFAULT 0,
    request_json  text,
    response_json text,
    created_at    timestamp with time zone NOT NULL DEFAULT now()
);

CREATE INDEX ix_tool_calls_agent_run ON tool_calls (agent_run_id);
