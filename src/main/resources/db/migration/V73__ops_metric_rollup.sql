-- ============================================================
-- BrainboxApi V73: operations metric rollups and sampled auto-approval audit (O1)
-- The backend owns its operational history in its own database. A scheduled job
-- aggregates the content/cost facts we already store into ops_metric_rollup for
-- the previous complete hour; the admin ops API serves the rollups (history) plus
-- live actuator/Micrometer values. No external observability service or new
-- dependency. moderation_outcomes.audit_sample flags the deterministic sample of
-- machine approvals a human should spot-check.
--
-- H2 (PostgreSQL mode) and PostgreSQL compatible: uuid PK with created_at,
-- updated_at and version from BaseEntity, a plain unique composite index for the
-- idempotent per-hour upsert, a double precision value and a boolean default.
-- ============================================================

CREATE TABLE ops_metric_rollup (
    id           uuid PRIMARY KEY,
    bucket_start timestamp with time zone NOT NULL,
    metric       varchar(96) NOT NULL,
    dimension    varchar(160) NOT NULL DEFAULT '',
    -- "value" is quoted because VALUE is an H2 reserved word; PostgreSQL accepts
    -- the quoted lowercase name unchanged.
    "value"      double precision NOT NULL,
    created_at   timestamp with time zone NOT NULL DEFAULT now(),
    updated_at   timestamp with time zone NOT NULL DEFAULT now(),
    version      bigint NOT NULL DEFAULT 0
);

-- One row per (hour, metric, dimension): recomputing an hour replaces it.
CREATE UNIQUE INDEX uq_ops_metric_rollup_bucket
    ON ops_metric_rollup (bucket_start, metric, dimension);

ALTER TABLE moderation_outcomes ADD COLUMN audit_sample boolean NOT NULL DEFAULT false;
