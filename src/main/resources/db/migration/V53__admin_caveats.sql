-- ============================================================
-- BrainboxApi V53: admin analytics trends, retrievable backups and an
-- audit trail that can be paged and pruned.
-- ============================================================

ALTER TABLE school_backups ADD COLUMN file_name varchar(255);
ALTER TABLE school_backups ADD COLUMN sha256 varchar(64);

CREATE TABLE school_performance_snapshots (
    id                  uuid PRIMARY KEY,
    school_id           uuid NOT NULL REFERENCES schools (id) ON DELETE CASCADE,
    term                varchar(16) NOT NULL,
    snapshot_year       integer NOT NULL,
    overall_performance double precision NOT NULL DEFAULT 0,
    class_averages      text,
    generated_at        timestamp with time zone NOT NULL DEFAULT now(),
    created_at          timestamp with time zone NOT NULL DEFAULT now(),
    updated_at          timestamp with time zone NOT NULL DEFAULT now(),
    version             bigint NOT NULL DEFAULT 0,
    CONSTRAINT uq_school_snapshot UNIQUE (school_id, term, snapshot_year)
);
